package upc.local.virtual_cli

import upc.local.thrift_java.directory
import upc.local.thrift_java.file
import upc.local.thrift_java.process_execution

import ammonite.ops._
import org.apache.thrift.{TServiceClient, TServiceClientFactory}
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocol}

import java.io.IOException
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try


case class IOServicesConfig(
  stdio: StdioStreams,
  memoryService: Path,
  directoryService: Path,
  processReapService: Path,
  executor: ExecutionContext,
)

case class Digest(fingerprint: String, sizeBytes: Long)

object Digest {
  val EMPTY_FINGERPRINT: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def empty: Digest = apply(EMPTY_FINGERPRINT, 0).get

  def apply(fingerprint: String, sizeBytes: Long): Try[FsState] = Try {
    if (fingerprint.length != 64) {
      throw BadDigest(
        s"length of fingerprint $fingerprint was not 64 bytes! was: ${fingerprint.length}")
    }
    if (sizeBytes < 0) {
      throw BadDigest(
        s"sizeBytes for digest with fingerprint $fingerprint was negative! was: $sizeBytes")
    }
    val digest = new DirectoryDigest()
    digest.setFingerprint(fingerprint)
    digest.setSize_bytes(sizeBytes)
    new Digest(digest)
  }
}

trait ViaThrift[ThriftObject, RealObject] {
  import ViaThrift._

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
}

case class DirectoryDigest(digest: Digest)
implicit object DirectoryViaThrift extends ViaThrift[directory.DirectoryDigest, DirectoryDigest] {
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
implicit object FileViaThrift extends ViaThrift[file.FileDigest, FileDigest] {
  def fromThrift(thrift: file.FileDigest): Try[FileDigest] = {
    val fingerprint = thrift.getFingerprint
    val sizeBytes = thrift.getSize_bytes
    Digest(fingerprint, sizeBytes)
      .map(FileDigest(_))
  }
  def toThrift(self: FileDigest); file.FileDigest = {
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
implicit object ProcessResultViaThrift extends ViaThrift[
  process_execution.ExecuteProcessResult,
  CompleteVirtualizedProcessResult,
] {
  def fromThrift(
    thrift: process_execution.ExecuteProcessResult,
  ): Try[CompleteVirtualizedProcessResult] = Try {
    val exitCode = ExitCode(thrift.getExit_code)
    val stdout = thrift.getStdout.fromThrift().get
    val stderr = thrift.getStderr.fromThrift().get
    val digest = thrift.getOutput_directory_digest.fromThrift().get
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
    result.setStdout(self.ioState.stdout.toThrift)
    result.setStderr(self.ioState.stderr.toThrift)
    result.setOutput_directory_digest(self.ioState.vfsDigest.toThrift)
    result
  }
}

class IOServices(val config: IOServicesConfig) extends AsyncCloseable {
  val IOServicesConfig(
    stdioStreams,
    memoryServicePath,
    directoryServicePath,
    processReapServicePath,
    implicit executor,
  ) = config
  // NB: Currently, these don't need to be closed or flushed at all, because everything happens in
  // memory, so no cleanup is required...yet!
  lazy val stdio = new Stdio(stdioStreams)

  private lazy val memorySocket = new ThriftUnixSocket(memoryServicePath)
  private lazy val memoryProtocol = new TBinaryProtocol(memorySocket.thriftSocket)
  private lazy val memoryClient = new MemoryMappingService.Client(memoryProtocol)

  private lazy val directorySocket = new ThriftUnixSocket(directoryServicePath)
  private lazy val directoryProtocol = new TBinaryProtocol(directorySocket.thriftSocket)
  private lazy val directoryClient = new DirectoryService.Client(directoryProtocol)

  def readFileMapping(): Future[FileMapping] = ???

  def writeFileMapping(fileMapping: FileMapping): Future[IOFinalState] = {
    val uploadVFS: Future[DirectoryDigest] = Future { ??? }
    val uploadStdout: Future[FileDigest] = Future { ??? }
    val uploadStderr: Future[FileDigest] = Future { ??? }
    IOFinalState(
      digest = fsDigest,
      stdout = stdoutDigest,
      stderr = stderrDigest,
    )
  }

  private lazy val processReapSocket = new ThriftUnixSocket(processReapServicePath)
  private lazy val processReapProtocol = new TBinaryProtocol(processReapSocket.thriftSocket)
  private lazy val processReapClient = new ProcessReapService.Client(processReapProtocol)

  def reapProcess(exitCode: ExitCode, fileMapping: FileMapping): Future[Unit] =
    writeFileMapping(fileMapping)
      .map(CompleteVirtualizedProcessResult(exitCode, _))
      .map(_.toThrift)
      .flatMap { thriftProcessResult =>
        Future { blocking {
          // TODO: If we keep this as a lazy val, it will only ever be instantiated exactly once,
          // right here, in the blocking {} block. That might be what we want, but it might not!!!
          processReapClient.reapProcess(thriftProcessResult)
        }}}

  override def asyncClose(): Future[Unit] =
    Future.sequence((
      memorySocket.asyncClose(), directorySocket.asyncClose(), processReapClient.asyncClose(),
    )).map { case ((), (), ()) => () }
}
