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

  implicit object PathGlobsViaThrift extends ViaThrift[thriftscala.PathGlobs, PathGlobs] {
    def fromThrift(thrift: thriftscala.PathGlobs): Try[PathGlobs] = Try {
      val thriftscala.PathGlobs(Some(includeGlobs), Some(excludeGlobs)) = thrift
      PathGlobs(
        include = includeGlobs,
        exclude = excludeGlobs)
    }
    def toThrift(self: PathGlobs): thriftscala.PathGlobs = {
      val PathGlobs(include, exclude) = self
      thriftscala.PathGlobs(
        includeGlobs = Some(include),
        excludeGlobs = Some(exclude),
      )
    }
  }

  implicit object ReducedExecuteProcessRequestViaThrift
      extends ViaThrift[thriftscala.ReducedExecuteProcessRequest, ReducedExecuteProcessRequest] {
    def fromThrift(
      thrift: thriftscala.ReducedExecuteProcessRequest,
    ): Try[ReducedExecuteProcessRequest] = Try {
      val thriftscala.ReducedExecuteProcessRequest(Some(argv), env, inputFiles) = thrift
      ReducedExecuteProcessRequest(
        argv = argv,
        env = env.map(_.toMap).getOrElse(Map.empty),
        inputFiles = inputFiles.map(_.fromThrift().get),
      )
    }
    def toThrift(self: ReducedExecuteProcessRequest): thriftscala.ReducedExecuteProcessRequest = {
      val ReducedExecuteProcessRequest(argv, env, inputFiles) = self
      thriftscala.ReducedExecuteProcessRequest(
        argv = Some(argv),
        env = Some(env),
        inputFiles = inputFiles.map(_.toThrift),
      )
    }
  }

  implicit object BasicExecuteProcessRequestViaThrift
      extends ViaThrift[thriftscala.BasicExecuteProcessRequest, BasicExecuteProcessRequest] {
    def fromThrift(
      thrift: thriftscala.BasicExecuteProcessRequest,
    ): Try[BasicExecuteProcessRequest] = Try {
      val thriftscala.BasicExecuteProcessRequest(Some(baseRequest), outputGlobs) = thrift
      BasicExecuteProcessRequest(
        baseRequest = baseRequest.fromThrift().get,
        outputGlobs = outputGlobs.map(_.fromThrift().get),
      )
    }
    def toThrift(self: BasicExecuteProcessRequest): thriftscala.BasicExecuteProcessRequest = {
      val BasicExecuteProcessRequest(baseRequest, outputGlobs) = self
      thriftscala.BasicExecuteProcessRequest(
        baseRequest = Some(baseRequest.toThrift),
        outputGlobs = outputGlobs.map(_.toThrift),
      )
    }
  }

  implicit object VirtualizedExecuteProcessRequestViaThrift
      extends ViaThrift[
    thriftscala.VirtualizedExecuteProcessRequest,
    VirtualizedExecuteProcessRequest,
  ] {
    def fromThrift(
      thrift: thriftscala.VirtualizedExecuteProcessRequest,
    ): Try[VirtualizedExecuteProcessRequest] = Try {
      val thriftscala.VirtualizedExecuteProcessRequest(
        Some(conjoinedRequests),
        daemonExecutionRequest,
      ) = thrift
      VirtualizedExecuteProcessRequest(
        conjoinedRequests = conjoinedRequests.map(_.fromThrift().get),
        daemonExecutionRequest = daemonExecutionRequest.map(_.fromThrift().get),
      )
    }
    def toThrift(
      self: VirtualizedExecuteProcessRequest,
    ): thriftscala.VirtualizedExecuteProcessRequest = {
      val VirtualizedExecuteProcessRequest(conjoinedRequests, daemonExecutionRequest) = self
      thriftscala.VirtualizedExecuteProcessRequest(
        conjoinedRequests = Some(conjoinedRequests.map(_.toThrift)),
        daemonExecutionRequest = daemonExecutionRequest.map(_.toThrift),
      )
    }
  }

  implicit object ProcessResultViaThrift extends ViaThrift[
    thriftscala.ExecuteProcessResult,
    ExecuteProcessResult,
  ] {
    def fromThrift(
      thrift: thriftscala.ExecuteProcessResult,
    ): Try[ExecuteProcessResult] = Try {
      val thriftscala.ExecuteProcessResult(
        Some(exitCode),
        Some(stdout),
        Some(stderr),
        Some(digest),
      ) = thrift
      ExecuteProcessResult(
        exitCode = ExitCode(exitCode),
        ioState = IOFinalState(
          vfsDigest = digest.fromThrift().get,
          stdout = stdout.fromThrift().get,
          stderr = stderr.fromThrift().get,
        ),
      )
    }
    def toThrift(self: ExecuteProcessResult): thriftscala.ExecuteProcessResult = {
      val ExecuteProcessResult(
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
    def toThrift(self: SubprocessRequestId): thriftscala.SubprocessRequestId = {
      val SubprocessRequestId(id) = self
      thriftscala.SubprocessRequestId(Some(id))
    }
  }

  implicit object ProcessReapResultViaThrift
      extends ViaThrift[thriftscala.ProcessReapResult, ProcessReapResult] {
    def fromThrift(thrift: thriftscala.ProcessReapResult): Try[ProcessReapResult] = Try {
      val thriftscala.ProcessReapResult(Some(exeResult), Some(id)) = thrift
      ProcessReapResult(
        exeResult = exeResult.fromThrift().get,
        id = id.fromThrift().get
      )
    }
    def toThrift(self: ProcessReapResult): thriftscala.ProcessReapResult = {
      val ProcessReapResult(exeResult, id) = self
      thriftscala.ProcessReapResult(
        exeResult = Some(exeResult.toThrift),
        id = Some(id.toThrift),
      )
    }
  }
}
