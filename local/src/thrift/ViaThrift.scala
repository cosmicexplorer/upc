package upc.local.thrift

import upc.local._
import upc.local.thriftscala.{process_execution => thriftscala}

import scala.util.Try


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

  implicit object DirectoryViaThrift
      extends ViaThrift[thriftscala.DirectoryDigest, DirectoryDigest] {
    def fromThrift(thrift: thriftscala.DirectoryDigest): Try[DirectoryDigest] = Try {
      val thriftscala.DirectoryDigest(Some(fingerprint), Some(sizeBytes)) = thrift
      DirectoryDigest(Digest.fromFingerprintHex(fingerprint, sizeBytes).get)
    }
    def toThrift(self: DirectoryDigest) = thriftscala.DirectoryDigest(
      fingerprint = Some(self.digest.fingerprintHex),
      sizeBytes = Some(self.digest.length),
    )
  }

  implicit object ShmKeyViaThrift extends ViaThrift[thriftscala.ShmKey, ShmKey] {
    def fromThrift(thrift: thriftscala.ShmKey): Try[ShmKey] = Try {
      val thriftscala.ShmKey(Some(fingerprint), Some(sizeBytes)) = thrift
      ShmKey(Digest.fromFingerprintHex(fingerprint, sizeBytes).get)
    }
    def toThrift(self: ShmKey) = thriftscala.ShmKey(
      fingerprint = Some(self.digest.fingerprintHex),
      sizeBytes = Some(self.digest.length),
    )
  }

  implicit object ProcessResultViaThrift extends ViaThrift[
    thriftscala.ExecuteProcessResult,
    CompleteVirtualizedProcessResult,
  ] {
    def fromThrift(
      thrift: thriftscala.ExecuteProcessResult,
    ): Try[CompleteVirtualizedProcessResult] = Try {
      val thriftscala.ExecuteProcessResult(
        Some(exitCode),
        Some(stdout),
        Some(stderr),
        Some(digest),
      ) = thrift
      CompleteVirtualizedProcessResult(
        exitCode = ExitCode(exitCode),
        ioState = IOFinalState(
          vfsDigest = digest.fromThrift().get,
          stdout = stdout.fromThrift().get,
          stderr = stderr.fromThrift().get,
        ),
      )
    }
    def toThrift(self: CompleteVirtualizedProcessResult): thriftscala.ExecuteProcessResult = {
      val CompleteVirtualizedProcessResult(
        ExitCode(exitCode),
        IOFinalState(digest, stdout, stderr),
      ) = self
      thriftscala.ExecuteProcessResult(
        exitCode = Some(exitCode),
        stdout = Some(stdout.toThrift),
        stderr = Some(stderr.toThrift),
        outputDirectoryDigest = Some(digest.toThrift)
      )
    }
  }

  implicit object SubprocessRequestIdViaThrift
      extends ViaThrift[thriftscala.SubprocessRequestId, SubprocessRequestId] {
    def fromThrift(thrift: thriftscala.SubprocessRequestId): Try[SubprocessRequestId] = Try {
      val thriftscala.SubprocessRequestId(Some(id)) = thrift
      SubprocessRequestId(id)
    }
    def toThrift(self: SubprocessRequestId): thriftscala.SubprocessRequestId = Try {
      val SubprocessRequestId(id) = self
      thriftscala.SubprocessRequestId(Some(id))
    }
  }
}
