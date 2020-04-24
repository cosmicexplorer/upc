package upc.local.virtual_cli.client

// import upc.local.{Digest, DirectoryDigest, ExitCode, FileDigest, IOFinalState, CompleteVirtualizedProcessResult}
import upc.local.Digest
import upc.local.thrift
import upc.local.thrift.ViaThrift._
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


sealed abstract class IOServicesError(message: String) extends Exception(message)
case class InitialFileMappingFailed(message: String) extends IOServicesError(message)


class IOServices(config: IOServicesConfig) extends AsyncCloseable {
  val IOServiceClients(processReapClient) = config.getClients()
  implicit val executor: ExecutionContext = config.executor

  def readFileMapping(): Future[FileMapping] = Future { blocking {
    val req = directory.ExpandDirectoriesRequest(Seq(config.initialDigest))

    val mapping = directory.DirectoryMapping.expand(req).get match {
      case directory.ExpandSucceeded(directory.ExpandDirectoriesMapping(mapping)) => mapping
    }
    if (mapping.size != 1) {
      throw InitialFileMappingFailed(s"initial mapping $mapping returned more than one digest!")
    }

    val (digest, pathStats) = mapping.toSeq.apply(0)
    if (digest != config.initialDigest) {
      throw InitialFileMappingFailed(
        s"returned digest $digest from attempted initial mapping did not match argument ${config.initialDigest}!")
    }

    FileMapping.fromPathStats(pathStats)
  }}

  def uploadBytes(bytes: Array[Byte]): Future[FileDigest] = Future { blocking {
    val inputMapping = memory.MemoryMapping.fromArray(bytes)
    val key = memory.Shm.getKey(memory.ShmGetKeyRequest(mapping)).get
    val allocateRequest = memory.ShmAllocateRequest(key, inputMapping)
    val outputMapping = memory.Shm.allocate(allocateRequest).get match {
      memory.AllocationSucceeded(_, _) => mapping
    }

  }}

  def writeIOState(
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
