package upc.local

import upc.local.thrift_java.process_execution

import java.io.IOException
import scala.util.Try


sealed abstract class EncodingError(message: String) extends IOException(message)
case class BadDigest(message: String) extends EncodingError(message)
case class BadSlice(message: String) extends EncodingError(message)


case class Digest(fingerprint: String, sizeBytes: Long) {
  if (fingerprint.length != 64) {
    throw BadDigest(
      s"length of fingerprint $fingerprint was not 64 bytes! was: ${fingerprint.length}")
  }
  if (sizeBytes < 0) {
    throw BadDigest(
      s"sizeBytes for digest with fingerprint $fingerprint was negative! was: $sizeBytes")
  }
}

object Digest {
  val EMPTY_FINGERPRINT: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def empty: Digest = apply(EMPTY_FINGERPRINT, 0)
}

case class DirectoryDigest(digest: Digest)

case class FileDigest(digest: Digest)

case class MemoryMappedRegionId(id: String)

case class LocalSlice(id: MemoryMappedRegionId, sizeBytes: Long) {
  if (sizeBytes < 0) {
    throw BadSlice(s"sizeBytes for local slice with id $id was negative! was: $sizeBytes")
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

  implicit object DirectoryViaThrift extends ViaThrift[directory.DirectoryDigest, DirectoryDigest] {
    def fromThrift(thrift: directory.DirectoryDigest): Try[DirectoryDigest] = Try {
      val fingerprint = thrift.getFingerprint
      val sizeBytes = thrift.getSize_bytes
      DirectoryDigest(Digest(fingerprint, sizeBytes))
    }
    def toThrift(self: DirectoryDigest): directory.DirectoryDigest = {
      val digest = new directory.DirectoryDigest
      digest.setFingerprint(self.digest.fingerprint)
      digest.setSize_bytes(self.digest.sizeBytes)
      digest
    }
  }

  implicit object FileViaThrift extends ViaThrift[file.FileDigest, FileDigest] {
    def fromThrift(thrift: file.FileDigest): Try[FileDigest] = Try {
      val fingerprint = thrift.getFingerprint
      val sizeBytes = thrift.getSize_bytes
      FileDigest(Digest(fingerprint, sizeBytes))
    }
    def toThrift(self: FileDigest): file.FileDigest = {
      val digest = new file.FileDigest
      digest.setFingerprint(self.digest.fingerprint)
      digest.setSize_bytes(self.digest.sizeBytes)
      digest
    }
  }

  implicit object MemoryMappedRegionIdViaThrift extends ViaThrift[
    file.MemoryMappedRegionId,
    MemoryMappedRegionId,
  ] {
    def fromThrift(thrift: file.MemoryMappedRegionId): Try[MemoryMappedRegionId] = Try {
      MemoryMappedRegionId(thrift.getId)
    }
    def toThrift(self: MemoryMappedRegionId): file.MemoryMappedRegionId = {
      val id = new file.MemoryMappedRegionId
      id.setId(self.id)
      id
    }
  }

  implicit object LocalSliceViaThrift extends ViaThrift[file.LocalSlice, LocalSlice] {
    def fromThrift(thrift: file.LocalSlice): Try[LocalSlice] = Try {
      val id: MemoryMappedRegionId = thrift.getId.fromThrift().get
      val sizeBytes: Long = thrift.getSize_bytes
      LocalSlice(id, sizeBytes)
    }
    def toThrift(self: LocalSlice): file.LocalSlice = {
      val localSlice = new file.LocalSlice
      localSlice.setId(self.id.toThrift)
      localSlice.setSize_bytes(self.sizeBytes)
      localSlice
    }
  }

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
      val result = new process_execution.ExecuteProcessResult
      result.setExit_code(self.exitCode.code)
      result.setStdout(self.ioState.stdout.toThrift)
      result.setStderr(self.ioState.stderr.toThrift)
      result.setOutput_directory_digest(self.ioState.vfsDigest.toThrift)
      result
    }
  }
}
