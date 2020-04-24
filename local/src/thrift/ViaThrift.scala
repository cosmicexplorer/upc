package upc.local.thrift

import upc.local._
import upc.local.thriftscala.process_execution._


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
      DirectoryDigest(Digest.fromFingerprintHex(fingerprint, sizeBytes))
    }
    def toThrift(self: DirectoryDigest): directory.DirectoryDigest = {
      val digest = new directory.DirectoryDigest
      digest.setFingerprint(self.digest.fingerprintHex)
      digest.setSize_bytes(self.digest.length)
      digest
    }
  }

  implicit object ShmKeyViaThrift extends ViaThrift[file.FileDigest, ShmKey] {
    def fromThrift(thrift: file.FileDigest): Try[ShmKey] = Try {
      val fingerprint = thrift.getFingerprint
      val sizeBytes = thrift.getSize_bytes
      ShmKey(Digest.fromFingerprintHex(fingerprint, sizeBytes))
    }
    def toThrift(self: ShmKey): file.FileDigest = {
      val digest = new file.FileDigest
      digest.setFingerprint(self.digest.fingerprintHex)
      digest.setSize_bytes(self.digest.length)
      digest
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
