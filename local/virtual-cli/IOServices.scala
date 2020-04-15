package upc.local.virtual_cli

import upc.local.thrift_java.directory
import upc.local.thrift_java.file
import upc.local.thrift_java.process_execution

import ammonite.ops._
import org.apache.thrift.protocol.TBinaryProtocol

import java.io.IOException
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try


case class IOServiceClients(
  memoryClient: file.MemoryMappingService.Iface,
  directoryClient: directory.DirectoryService.Iface,
  processReapClient: process_execution.ProcessReapService.Iface,
)


class IOServicesConfig(
  memoryServicePath: Path,
  directoryServicePath: Path,
  processReapServicePath: Path,
  implicit val executor: ExecutionContext,
) extends AsyncCloseable {
  private lazy val memorySocket = new ThriftUnixSocket(memoryServicePath)
  private lazy val memoryProtocol = new TBinaryProtocol(memorySocket.thriftSocket)
  private lazy val memoryClient = new file.MemoryMappingService.Client(memoryProtocol)

  private lazy val directorySocket = new ThriftUnixSocket(directoryServicePath)
  private lazy val directoryProtocol = new TBinaryProtocol(directorySocket.thriftSocket)
  private lazy val directoryClient = new directory.DirectoryService.Client(directoryProtocol)

  private lazy val processReapSocket = new ThriftUnixSocket(processReapServicePath)
  private lazy val processReapProtocol = new TBinaryProtocol(processReapSocket.thriftSocket)
  private lazy val processReapClient = new process_execution.ProcessReapService.Client(processReapProtocol)

  def getClients() = IOServiceClients(
    memoryClient = memoryClient,
    directoryClient = directoryClient,
    processReapClient = processReapClient,
  )

  import AsyncCloseable.Implicits._
  override def asyncClose(): Future[Unit] = for {
    () <- memorySocket.asyncClose()
    () <- directorySocket.asyncClose()
    () <- processReapSocket.asyncClose()
  } yield ()
}

sealed abstract class IOServicesError(message: String) extends IOException(message)
case class BadDigest(message: String) extends IOServicesError(message)

case class Digest(fingerprint: String, sizeBytes: Long)

object Digest {
  val EMPTY_FINGERPRINT: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def empty: Digest = apply(EMPTY_FINGERPRINT, 0).get

  def apply(fingerprint: String, sizeBytes: Long): Try[Digest] = Try {
    if (fingerprint.length != 64) {
      throw BadDigest(
        s"length of fingerprint $fingerprint was not 64 bytes! was: ${fingerprint.length}")
    }
    if (sizeBytes < 0) {
      throw BadDigest(
        s"sizeBytes for digest with fingerprint $fingerprint was negative! was: $sizeBytes")
    }
    new Digest(fingerprint, sizeBytes)
  }
}

trait ViaThrift[ThriftObject, RealObject] {
  def fromThrift(thrift: ThriftObject): Try[RealObject]
  def toThrift(self: RealObject): ThriftObject
}
object ViaThrift {
  implicit class ThriftObjectWrapper[ThriftObject, RealObject](thrift: ThriftObject)(
    implicit viaThrift: ViaThrift[ThriftObject, RealObject]
  ) {
    def fromThrift(): Try[RealObject] = viaThrift.fromThrift(thrift)
  }
  implicit class RealObjectWrapper[ThriftObject, RealObject](real: RealObject)(
    implicit viaThrift: ViaThrift[ThriftObject, RealObject]
  ) {
    def toThrift: ThriftObject = viaThrift.toThrift(real)
  }

  object Instances {
    implicit val dirViaThrift: ViaThrift[directory.DirectoryDigest, DirectoryDigest] =
      DirectoryViaThrift
    implicit val fileViaThrift: ViaThrift[file.FileDigest, FileDigest] = FileViaThrift
    implicit val processResultViaThrift: ViaThrift[
      process_execution.ExecuteProcessResult,
      CompleteVirtualizedProcessResult,
    ] = ProcessResultViaThrift
  }
}

case class DirectoryDigest(digest: Digest)
object DirectoryViaThrift extends ViaThrift[directory.DirectoryDigest, DirectoryDigest] {
  def fromThrift(thrift: directory.DirectoryDigest): Try[DirectoryDigest] = {
    val fingerprint = thrift.getFingerprint
    val sizeBytes = thrift.getSize_bytes
    Digest(fingerprint, sizeBytes)
      .map(DirectoryDigest(_))

  }
  def toThrift(self: DirectoryDigest): directory.DirectoryDigest = {
    val digest = new directory.DirectoryDigest()
    digest.setFingerprint(self.digest.fingerprint)
    digest.setSize_bytes(self.digest.sizeBytes)
    digest
  }
}

case class FileDigest(digest: Digest)
object FileViaThrift extends ViaThrift[file.FileDigest, FileDigest] {
  def fromThrift(thrift: file.FileDigest): Try[FileDigest] = {
    val fingerprint = thrift.getFingerprint
    val sizeBytes = thrift.getSize_bytes
    Digest(fingerprint, sizeBytes)
      .map(FileDigest(_))
  }
  def toThrift(self: FileDigest): file.FileDigest = {
    val digest = new file.FileDigest()
    digest.setFingerprint(self.digest.fingerprint)
    digest.setSize_bytes(self.digest.sizeBytes)
    digest
  }
}


case class IOFinalState(
  vfsDigest: DirectoryDigest,
  stdout: FileDigest,
  stderr: FileDigest,
)

case class ExitCode(code: Int)

case class CompleteVirtualizedProcessResult(
  exitCode: ExitCode,
  ioState: IOFinalState,
)
object ProcessResultViaThrift extends ViaThrift[
  process_execution.ExecuteProcessResult,
  CompleteVirtualizedProcessResult,
] {
  def fromThrift(
    thrift: process_execution.ExecuteProcessResult,
  ): Try[CompleteVirtualizedProcessResult] = Try {
    val exitCode = ExitCode(thrift.getExit_code)
    val stdout = FileViaThrift.fromThrift(thrift.getStdout).get
    val stderr = FileViaThrift.fromThrift(thrift.getStderr).get
    val digest = DirectoryViaThrift.fromThrift(thrift.getOutput_directory_digest).get
    CompleteVirtualizedProcessResult(
      exitCode = exitCode,
      ioState = IOFinalState(
        vfsDigest = digest,
        stdout = stdout,
        stderr = stderr,
      ),
    )
  }
  def toThrift(self: CompleteVirtualizedProcessResult): process_execution.ExecuteProcessResult = {
    val result = new process_execution.ExecuteProcessResult()
    result.setExit_code(self.exitCode.code)
    result.setStdout(FileViaThrift.toThrift(self.ioState.stdout))
    result.setStderr(FileViaThrift.toThrift(self.ioState.stderr))
    result.setOutput_directory_digest(DirectoryViaThrift.toThrift(self.ioState.vfsDigest))
    result
  }
}

class IOServices(config: IOServicesConfig) extends AsyncCloseable {
  val IOServiceClients(memoryClient, directoryClient, processReapClient) = config.getClients()
  implicit val executor: ExecutionContext = config.executor

  def readFileMapping(): Future[FileMapping] = ???

  private def writeIOState(
    fileMapping: FileMapping,
    stdioResults: StdioResults,
  ): Future[IOFinalState] = {
    val uploadVFS: Future[DirectoryDigest] = Future { ??? }
    val uploadStdout: Future[FileDigest] = Future { ??? }
    val uploadStderr: Future[FileDigest] = Future { ??? }

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
