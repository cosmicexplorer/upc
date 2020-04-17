package upc.local.services

import upc.local.{Digest, DirectoryDigest, ExitCode, FileDigest, IOFinalState, LocalSlice}
import upc.local.ViaThrift._
import upc.local.memory.LibMemory
import upc.local.thrift_java.directory
import upc.local.thrift_java.file
import upc.local.thrift_java.process_execution

import org.apache.thrift.async.AsyncMethodCallback

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.{Try, Success, Failure}


sealed abstract class ServiceFailure(message: String, cause: Throwable = null)
    extends IOException(message, cause)


object ThriftAsyncExt {
  implicit class AsyncMethodTransformer[T](fut: Future[T])(implicit executor: ExecutionContext) {
    def applyHandler(handler: AsyncMethodCallback[T]): Unit = {
      fut.transformWith {
        case Success(x) => Future { blocking { handler.onComplete(x) } }
        case Failure(e) => Future { blocking { handler.onError(e) } }
      }
      // We don't want to block on the future here -- .transformWith has already scheduled the work
      // to occur on the executor, so we can just return here.
      ()
    }
  }
}

class ProcessExecutionService extends process_execution.ProcessExecutionService.AsyncIface {
  def executeProcesses(
    request: process_execution.VirtualizedExecuteProcessRequest,
    handler: AsyncMethodCallback[process_execution.ExecuteProcessResult],
  ): Unit = {
    val doLogic: Future[process_execution.ExecuteProcessResult] = Future {
      val parsed = request.fromThrift().get
    }
    doLogic.applyHandler(handler)
  }
}


class ProcessReapService extends process_execution.ProcessReapService.AsyncIface {
}
