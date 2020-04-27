package upc.local.virtual_cli.server

import upc.local._
import upc.local.directory._
import upc.local.memory._
import upc.local.thrift.ViaThrift
import upc.local.thrift.ViaThrift._
import upc.local.thriftscala.{process_execution => thriftscala}
import upc.local.virtual_cli.client.MainWrapper

import com.twitter.finatra.thrift.Controller
import com.twitter.util.{Future, FuturePool, Promise}
import com.twitter.scrooge.{Request, Response}

import java.util.UUID
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}


case class DaemonExecuteProcessRequest(inner: thriftscala.ReducedExecuteProcessRequest)

case class DaemonExecuteProcessResult(exitCode: Int, stdout: String, stderr: String)


class ProcessExecutionController extends Controller(ProcessExecutionService) {
  import ProcessExecutionService._

  val liveDaemonExecuteProcesses: mutable.Set[DaemonExecuteProcessRequest] = mutable.Set.empty

  val liveSubprocesses: mutable.Map[
    SubprocessRequestId,
    Promise[CompleteVirtualizedProcessResult],
  ] = mutable.Map.empty

  def maybeExecuteDaemon(req: DaemonExecuteProcessRequest): Unit = {
    liveDaemonExecuteProcesses.synchronized {
      // If there is *no* existing daemon process matching this request (or if there was previously,
      // but it has since exited for whatever reason), execute the request!
      if (!liveDaemonExecuteProcesses.contains(req)) {
        liveDaemonExecuteProcesses.addOne(req)

        val DaemonExecuteProcessRequest(inner) = req
        val builder = Process(
          command = inner.argv.get,
          cwd = None,
          extraEnv=(inner.env.getOrElse(Map.empty).toSeq):_*)
        val logger = ProcessLogger { logLine =>
          log(s"LOG: $logLine (from daemon process $req)")
        }

        val executeProcess: Future[Unit] = FuturePool.unboundedPool {
          val exitCode = builder.run(logger).exitValue()
          log(s"EXIT: code: $exitCode for daemon process $req")
        }.ensure {
          liveDaemonExecuteProcesses.synchronized {
            liveDaemonExecuteProcesses.subtractOne(req)
          }
        }

        log(s"scheduled daemon process execution for $req at $executeProcess")
      } else {
        log(s"daemon process execution already existed for $req! reusing!")
      }
    }
  }

  def executeSubprocess(
    req: thriftscala.BasicExecuteProcessRequest,
  ): Future[CompleteVirtualizedProcessResult] = {
    val vfsDigest = req.inputFiles match {
      case Some(x) => x.fromThrift().get
      case None => DirectoryDigest(Digest.empty)
    }
    val requestId = SubprocessRequestId(UUID.randomUUID().toString)

    val env = req.env.getOrElse(Map.empty) ++ Seq(
      (MainWrapper.VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR -> vfsDigest.digest.fingerprintHex),
      (MainWrapper.VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR -> vfsDigest.digest.length),
      (MainWrapper.PROCESS_REAP_SERVICE_THRIFT_SOCKET_PORT_ENV_VAR ->
        ProcessExecutionServer.defaultThriftPortNum.toString),
      (MainWrapper.SUBPROCESS_REQUEST_ID_ENV_VAR -> requestId.inner),
    )

    val builder = Process(
      command = req.argv.get,
      cwd = None,
      extraEnv=(env.toSeq):_*)
    val logger = ProcessLogger { logLine =>
      log(s"LOG(SUBPROC): uncaptured $logLine from subprocess $req (this means the virtualization layer is failing!!)")
    }

    val promise: Promise[CompleteVirtualizedProcessResult] = Promise()
    liveSubprocesses.synchronized {
      liveSubprocesses(requestId) = promise
    }

    val executeProcess: Future[Unit] = FuturePool.unboundedPool {
      val exitCode = builder.run(logger).exitValue()
      log(s"EXIT(SUBPROC): code: $exitCode for subprocess $req")
    }
    executeProcess
      .flatMap(() => promise)
  }

  def getCattedOutput(keys: Seq[ShmKey]): Try[ShmKey] = Try {
    val cattedBytes = keys
      .map(Shm.retrieveBytes(_).get)
      // TODO: this is always copying both arrays each time to concatenate them with ++, which is
      // quadratic.
      .foldLeft(Array[Byte]()) { (acc, cur) => acc ++ cur }
    val (key, _) = Shm.allocateBytes(cattedBytes).get
    key
  }

  handle(ExecuteProcesses).withFn { request: Request[ExecuteProcesses.Args] =>
    val req = request.args.executeProcessRequest

    // If there is a daemon execution request specified, see if it's already running, and start one
    // up if not.
    req.daemonExecutionRequest
      .map(DaemonExecuteProcessRequest(_))
      .foreach(maybeExecuteDaemon(_))

    val completedProcesses: Future[Seq[CompleteVirtualizedProcessResult]] = Future.collect(
      req.conjoinedRequests.get.map(executeSubprocess(_)))

    // Concatenate the output streams and merge the directory digests.
    // TODO: allow an interactive/incremental mode with interleaved output, as well as supporting
    // stdin!
    val mergedResult: Future[CompleteVirtualizedProcessResult] = completedProcesses.map { results =>
      // Get the first nonzero exit code and use that, otherwise return zero.
      val mergedExitCode: Int = results.map(_.exitCode.code).find(_ != 0).getOrElse(0)
      val cattedStdout: ShmKey = getCattedOutput(results.map(_.ioState.stdout)).get
      val cattedStderr: ShmKey = getCattedOutput(results.map(_.ioState.stderr)).get
      val mergedDigests: DirectoryDigest = DirectoryMapping.mergeDigests(
        results.map(_.ioState.vfsDigest)).get

      CompleteVirtualizedProcessResult(
        exitCode = ExitCode(mergedExitCode),
        ioState = IOFinalState(
          stdout = cattedStdout,
          stderr = cattedStderr,
          vfsDigest = mergedDigests,
        ),
      )
    }

    mergedResult.map(_.toThrift).map(Response(_))
  }

  handle(ReapProcess).withFn { request: Request[ReapProcess.Args] =>
    val result = request.args.processReapResult
    val exeResult = result.exeResult.get.fromThrift().get
    val id = result.id.get.fromThrift().get
    val promise = liveSubprocesses.synchronized {
      val ret = liveSubprocesses(id)
      liveSubprocesses.subtractOne(id)
      ret
    }
    promise.setValue(exeResult)

    Future(Response(()))
  }
}
