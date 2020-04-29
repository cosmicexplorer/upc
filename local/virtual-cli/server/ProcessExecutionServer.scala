package upc.local.virtual_cli.server

import upc.local.thriftjava.process_execution._
import upc.local.thrift_socket.ThriftUnixServer

import ammonite.ops._
import org.apache.thrift.server.TThreadPoolServer

import scala.concurrent.{Future, blocking, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try


case class ThriftSocketServer(server: ThriftUnixServer, path: Path)

object ThriftSocketServer {
  def fromPath(path: Path): Try[ThriftSocketServer] = Try {
    ThriftSocketServer(ThriftUnixServer(path).get, path)
  }
}

case class ProcessExecutionServerFoundation(
  processExecutionService: ThriftSocketServer,
  processReapService: ThriftSocketServer,
)

object ProcessExecutionServerMain {
  def main(args: Array[String]): Unit = {
    val (exeServer, reapServer) = args match {
      case Array(exeServerPath, reapServerPath) => (
        ThriftSocketServer.fromPath(Path(exeServerPath)).get,
        ThriftSocketServer.fromPath(Path(reapServerPath)).get,
      )
      case x => throw new RuntimeException(s"unrecognized command-line arguments $x. expected: [exe-server-path] [process-reap-server-path]")
    }

    val foundation = ProcessExecutionServerFoundation(
      processExecutionService = exeServer,
      processReapService = reapServer,
    )
    val serverImpl = new ProcessExecutionServer(foundation)
    val futures = serverImpl.run()
    val Seq((), ()) = Await.result(Future.sequence(futures), Duration.Inf)
  }
}

class ProcessExecutionServer(foundation: ProcessExecutionServerFoundation) {
  val logger = new Logger
  import logger._

  val ThriftSocketServer(reapSocket, reapServerPath) = foundation.processReapService
  lazy val handler = new ProcessExecutionController(reapServerPath)

  val ThriftSocketServer(exeSocket, exeServerPath) = foundation.processExecutionService
  lazy val exeProcessor = new ProcessExecutionService.Processor(handler)
  // From the tutorial JavaServer.java in the thrift repo.
  lazy val exeServer = new TThreadPoolServer(
    // Uses TBinaryProtocol.Factory by default!
    new TThreadPoolServer.Args(exeSocket).processor(exeProcessor))

  lazy val reapProcessor = new ProcessReapService.Processor(handler)
  lazy val reapServer = new TThreadPoolServer(
    new TThreadPoolServer.Args(reapSocket).processor(reapProcessor))

  def run(): Seq[Future[Unit]] = {
    val runExeServer = Future { blocking {
      info(s"starting process execution server at $exeServerPath...")
      exeServer.serve()
    }}
    val runReapServer = Future { blocking {
      info(s"starting process reap server at $reapServerPath...")
      reapServer.serve()
    }}

    Seq(runExeServer, runReapServer)
  }

  def stop(): Unit = {
    exeServer.stop()
    reapServer.stop()
  }
}
