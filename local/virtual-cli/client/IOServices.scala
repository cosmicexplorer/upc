package upc.local.virtual_cli.client

// import upc.local.{Digest, DirectoryDigest, ExitCode, FileDigest, IOFinalState, CompleteVirtualizedProcessResult}
import upc.local
import upc.local.ViaThrift._
import upc.local.directory
import upc.local.memory
import upc.local.thrift_java.process_execution
import upc.local.virtual_cli.util.AsyncCloseable

import ammonite.ops._
import org.apache.thrift.protocol.TBinaryProtocol

import java.io.IOException
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try


case class IOServiceClients(
  processReapClient: process_execution.ProcessReapService.Iface,
)


class IOServicesConfig(
  val initialDigest: directory.DirectoryDigest,
  processReapServicePath: Path,
  implicit val executor: ExecutionContext,
) extends AsyncCloseable {
  private lazy val processReapSocket = new ThriftUnixSocket(processReapServicePath)
  private lazy val processReapProtocol = new TBinaryProtocol(processReapSocket.thriftSocket)
  private lazy val processReapClient = new process_execution.ProcessReapService.Client(processReapProtocol)

  def getClients() = IOServiceClients(
    processReapClient = processReapClient,
  )

  import AsyncCloseable.Implicits._
  override def asyncClose(): Future[Unit] = processReapSocket.asyncClose()
}


class IOServices(config: IOServicesConfig) extends AsyncCloseable {
  val IOServiceClients(processReapClient) = config.getClients()
  implicit val executor: ExecutionContext = config.executor

  def readFileMapping(): Future[FileMapping] = ???

  private def writeIOState(
    fileMapping: FileMapping,
    stdioResults: StdioResults,
  ): Future[IOFinalState] = {
    val uploadVFS: Future[DirectoryDigest] = Future { blocking { ??? } }
    val uploadStdout: Future[FileDigest] = Future { blocking { ??? } }
    val uploadStderr: Future[FileDigest] = Future { blocking { ??? } }

    for {
      vfsDigest <- uploadVFS
      stdoutDigest <- uploadStdout
      stderrDigest <- uploadStderr
    } yield IOFinalState(
      vfsDigest = vfsDigest,
      stdout = stdoutDigest,
      stderr = stderrDigest,
    )
  }

  def reapProcess(
    exitCode: ExitCode,
    stdioResults: StdioResults,
    fileMapping: FileMapping,
  ): Future[Unit] = for {
    ioState <- writeIOState(fileMapping, stdioResults)
    () <- Future { blocking {
      val result = CompleteVirtualizedProcessResult(exitCode, ioState)
      val thriftProcessResult = ProcessResultViaThrift.toThrift(result)
      // TODO: If we keep this as a lazy val, it will only ever be instantiated exactly once,
      // right here, in the blocking {} block. That might be what we want, but it might not!!!
      processReapClient.reapProcess(thriftProcessResult)
    }}
  } yield ()

  override def asyncClose(): Future[Unit] = config.asyncClose()
}