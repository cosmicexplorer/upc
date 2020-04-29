package upc.local.virtual_cli.server

import upc.local._
import upc.local.directory._
import upc.local.memory._
import upc.local.thrift.ViaThrift._
import upc.local.thriftjava.{process_execution => thriftjava}
import upc.local.virtual_cli.client.MainWrapperEnvVars

import ammonite.ops._

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try


case class DaemonExecuteProcessRequest(inner: ReducedExecuteProcessRequest)

case class DaemonExecuteProcessResult(exitCode: Int, stdout: String, stderr: String)


class ProcessExecutionController(reapSocketPath: Path)
    extends thriftjava.ProcessExecutionService.Iface
    with thriftjava.ProcessReapService.Iface {

  val logger = new Logger
  import logger._

  val liveDaemonExecuteProcesses: mutable.Set[DaemonExecuteProcessRequest] = mutable.Set.empty

  val liveSubprocesses: mutable.Map[
    SubprocessRequestId,
    Promise[ExecuteProcessResult],
  ] = mutable.Map.empty

  def maybeExecuteDaemon(req: DaemonExecuteProcessRequest): Unit = {
    liveDaemonExecuteProcesses.synchronized {
      // If there is *no* existing daemon process matching this request (or if there was previously,
      // but it has since exited for whatever reason), execute the request!
      if (!liveDaemonExecuteProcesses.contains(req)) {
        liveDaemonExecuteProcesses += req

        val DaemonExecuteProcessRequest(inner) = req
        val builder = Process(
          command = inner.argv,
          cwd = None,
          extraEnv=(inner.env.toSeq):_*)
        val logger = ProcessLogger { logLine =>
          info(s"LOG: $logLine (from daemon process $req)")
        }

        val executeProcess: Future[Unit] = Future {
          val exitCode = builder.run(logger).exitValue()
          debug(s"EXIT: code: $exitCode for daemon process $req")
        }
        executeProcess.onComplete { _ =>
          liveDaemonExecuteProcesses.synchronized {
            liveDaemonExecuteProcesses -= req
          }
        }

        debug(s"scheduled daemon process execution for $req at $executeProcess")
      } else {
        debug(s"daemon process execution already existed for $req! reusing!")
      }
    }
  }

  def executeSubprocess(
    request: BasicExecuteProcessRequest,
  ): Future[ExecuteProcessResult] = {
    // FIXME: incorporate PathGlobs into subprocess output!!
    val BasicExecuteProcessRequest(req, _outputGlobs) = request
    val vfsDigest = req.inputFiles.getOrElse(DirectoryDigest(Digest.empty))
    val requestId = SubprocessRequestId(UUID.randomUUID().toString)

    val env = req.env ++ Seq(
      (MainWrapperEnvVars.VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR -> vfsDigest.digest.fingerprintHex),
      (MainWrapperEnvVars.VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR -> vfsDigest.digest.sizeBytes.toString),
      (MainWrapperEnvVars.PROCESS_REAP_SERVICE_THRIFT_SOCKET_PATH_ENV_VAR -> reapSocketPath.toString),
      (MainWrapperEnvVars.SUBPROCESS_REQUEST_ID_ENV_VAR -> requestId.inner),
    )

    val builder = Process(
      command = req.argv,
      cwd = None,
      extraEnv=(env.toSeq):_*)
    val logger = ProcessLogger { logLine =>
      warn(s"LOG(SUBPROC): uncaptured $logLine from subprocess $req (this means the virtualization layer is failing!!)")
    }

    val promise: Promise[ExecuteProcessResult] = Promise()
    liveSubprocesses.synchronized {
      liveSubprocesses(requestId) = promise
    }

    val executeProcess: Future[Unit] = Future {
      val exitCode = builder.run(logger).exitValue()
      throw new Exception(s"exitCode: $exitCode")
      debug(s"EXIT(SUBPROC): code: $exitCode for subprocess $req")
    }
    executeProcess
      .flatMap(Unit => promise.future)
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

  override def executeProcesses(
    request: thriftjava.VirtualizedExecuteProcessRequest,
  ): thriftjava.ExecuteProcessResult = {
    val req = request.fromThrift().get

    // If there is a daemon execution request specified, see if it's already running, and start one
    // up if not.
    req.daemonExecutionRequest
      .map(DaemonExecuteProcessRequest(_))
      .foreach(maybeExecuteDaemon(_))

    val completedProcesses: Future[Seq[ExecuteProcessResult]] = Future.sequence(
      req.conjoinedRequests.map(executeSubprocess(_)))

    // Concatenate the output streams and merge the directory digests.
    // TODO: allow an interactive/incremental mode with interleaved output, as well as supporting
    // stdin!
    val mergedResult: Future[ExecuteProcessResult] = completedProcesses.map { results =>
      // Get the first nonzero exit code and use that, otherwise return zero.
      val mergedExitCode: Int = results.map(_.exitCode.code).find(_ != 0).getOrElse(0)
      val cattedStdout: ShmKey = getCattedOutput(results.map(_.ioState.stdout)).get
      val cattedStderr: ShmKey = getCattedOutput(results.map(_.ioState.stderr)).get
      val mergedDigests: DirectoryDigest = DirectoryMapping.mergeDigests(
        results.map(_.ioState.vfsDigest)).get

      ExecuteProcessResult(
        exitCode = ExitCode(mergedExitCode),
        ioState = IOFinalState(
          stdout = cattedStdout,
          stderr = cattedStderr,
          vfsDigest = mergedDigests,
        ),
      )
    }

    Await.result(mergedResult.map(_.toThrift), 5.minutes)
  }

  override def reapProcess(request: thriftjava.ProcessReapResult): Unit = {
    val ProcessReapResult(exeResult, id) = request.fromThrift().get

    info(s"exeResult: $exeResult")

    val promise = liveSubprocesses.synchronized {
      val ret = liveSubprocesses(id)
      liveSubprocesses -= id
      ret
    }
    promise.success(exeResult)
    ()
  }
}
