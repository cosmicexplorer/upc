package upc.local.virtual_cli.server

import upc.local.thriftscala.process_execution._

import com.twitter.finatra.thrift.Controller
import com.twitter.util.Future
import com.twitter.scrooge.{Request, Response}

import scala.collection.mutable


case class DaemonExecuteProcessRequest(inner: BasicExecuteProcessRequest)

case class DaemonExecuteProcessResult(exitCode: Int, stdout: String, stderr: String)


class ProcessExecutionController extends Controller(ProcessExecutionService) {
  import ProcessExecutionService._

  val liveDaemonExecuteProcesses: mutable.Set[DaemonExecuteProcessRequest] = mutable.Set.empty

  def maybeExecuteDaemon(req: DaemonExecuteProcessRequest): Unit = {
    liveDaemonExecuteProcesses.synchronized {
      // If there is *no* existing daemon process matching this request, execute the request!
      if (!liveDaemonExecuteProcesses.contains(req)) {
        liveDaemonExecuteProcesses.addOne(req)
        val DaemonExecuteProcessRequest(inner) = req
        executeBasic(inner)
      }
    }
  }

  def executeBasic(req: BasicExecuteProcessRequest): Unit = ???

  def executeSubprocess(req: BasicExecuteProcessRequest): Future[ExecuteProcessResult] = ???

  handle(ExecuteProcesses).withFn { request: Request[ExecuteProcesses.Args] =>
    val req = request.args.executeProcessRequest

    // If there is a daemon execution request specified, see if it's already running, and start one
    // up if not.
    req.daemonExecutionRequest.foreach(maybeExecuteDaemon(_))

    val completedProcesses: Future[Seq[ExecuteProcessResult]] = Future.collect(
      req.conjoinedRequests.get.map(executeSubprocess(_)))

    // TODO: allow an interactive/incremental mode with interleaved output, as well as supporting
    // stdin!
    // For now, we just concatenate the output streams and merge the directory digests.
  }

  handle(ReapProcess).withFn { request: Request[ReapProcess.Args] =>
    ???
  }
}
