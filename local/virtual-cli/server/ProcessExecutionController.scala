package upc.local.virtual_cli.server

import upc.local.thrift_scala.process_execution._

import com.twitter.finatra.thrift.Controller
import com.twitter.util.Future
import com.twitter.scrooge.{Request, Response}


class ProcessExecutionController extends Controller(ProcessExecutionService) {
  import ProcessExecutionService._

  handle(ExecuteProcesses).withFn { request: Request[ExecuteProcesses.Args] =>
    ???
  }

  handle(ReapProcess).withFn { request: Request[ReapProcess.Args] =>
    ???
  }
}
