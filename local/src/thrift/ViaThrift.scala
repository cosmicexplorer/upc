package upc.local.thrift

import upc.local._
import upc.local.thriftjava.{process_execution => thriftjava}

import scala.collection.JavaConverters._
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
      extends ViaThrift[thriftjava.DirectoryDigest, DirectoryDigest] {
    def fromThrift(thrift: thriftjava.DirectoryDigest): Try[DirectoryDigest] = Try {
      val fingerprint = thrift.getFingerprint
      val sizeBytes = thrift.getSize_bytes
      DirectoryDigest(Digest.fromFingerprintHex(fingerprint, sizeBytes).get)
    }
    def toThrift(self: DirectoryDigest) = {
      val DirectoryDigest(digest) = self
      val ret = new thriftjava.DirectoryDigest
      ret.setFingerprint(digest.fingerprintHex)
      ret.setSize_bytes(digest.sizeBytes)
      ret
    }
  }

  implicit object ShmKeyViaThrift extends ViaThrift[thriftjava.ShmKey, ShmKey] {
    def fromThrift(thrift: thriftjava.ShmKey): Try[ShmKey] = Try {
      val fingerprint = thrift.getFingerprint
      val sizeBytes = thrift.getSize_bytes
      ShmKey(Digest.fromFingerprintHex(fingerprint, sizeBytes).get)
    }
    def toThrift(self: ShmKey) = {
      val ShmKey(digest) = self
      val ret = new thriftjava.ShmKey
      ret.setFingerprint(digest.fingerprintHex)
      ret.setSize_bytes(digest.sizeBytes)
      ret
    }
  }

  implicit object PathGlobsViaThrift extends ViaThrift[thriftjava.PathGlobs, PathGlobs] {
    def fromThrift(thrift: thriftjava.PathGlobs): Try[PathGlobs] = Try {
      val includeGlobs = thrift.getInclude_globs.asScala.toSeq
      val excludeGlobs = Option(thrift.getExclude_globs).map(_.asScala.toSeq).getOrElse(Seq())
      PathGlobs(
        include = includeGlobs,
        exclude = excludeGlobs)
    }
    def toThrift(self: PathGlobs): thriftjava.PathGlobs = {
      val PathGlobs(include, exclude) = self
      val ret = new thriftjava.PathGlobs
      ret.setInclude_globs(include.asJava)
      ret.setExclude_globs(exclude.asJava)
      ret
    }
  }

  implicit object ReducedExecuteProcessRequestViaThrift
      extends ViaThrift[thriftjava.ReducedExecuteProcessRequest, ReducedExecuteProcessRequest] {
    def fromThrift(
      thrift: thriftjava.ReducedExecuteProcessRequest,
    ): Try[ReducedExecuteProcessRequest] = Try {
      val argv = thrift.getArgv.asScala
      val env = Option(thrift.getEnv).map(_.asScala)
      val inputFiles = Option(thrift.getInput_files)
      ReducedExecuteProcessRequest(
        argv = argv,
        env = env.map(_.toMap).getOrElse(Map.empty),
        inputFiles = inputFiles.map(_.fromThrift().get),
      )
    }
    def toThrift(self: ReducedExecuteProcessRequest): thriftjava.ReducedExecuteProcessRequest = {
      val ReducedExecuteProcessRequest(argv, env, inputFiles) = self
      val ret = new thriftjava.ReducedExecuteProcessRequest
      ret.setArgv(argv.asJava)
      ret.setEnv(env.asJava)
      inputFiles.foreach(files => ret.setInput_files(files.toThrift))
      ret
    }
  }

  implicit object BasicExecuteProcessRequestViaThrift
      extends ViaThrift[thriftjava.BasicExecuteProcessRequest, BasicExecuteProcessRequest] {
    def fromThrift(
      thrift: thriftjava.BasicExecuteProcessRequest,
    ): Try[BasicExecuteProcessRequest] = Try {
      val baseRequest = thrift.getBase_request
      val outputGlobs = Option(thrift.getOutput_globs)
      BasicExecuteProcessRequest(
        baseRequest = baseRequest.fromThrift().get,
        outputGlobs = outputGlobs.map(_.fromThrift().get),
      )
    }
    def toThrift(self: BasicExecuteProcessRequest): thriftjava.BasicExecuteProcessRequest = {
      val BasicExecuteProcessRequest(baseRequest, outputGlobs) = self
      val ret = new thriftjava.BasicExecuteProcessRequest
      ret.setBase_request(baseRequest.toThrift)
      outputGlobs.foreach(globs => ret.setOutput_globs(globs.toThrift))
      ret
    }
  }

  implicit object VirtualizedExecuteProcessRequestViaThrift
      extends ViaThrift[
    thriftjava.VirtualizedExecuteProcessRequest,
    VirtualizedExecuteProcessRequest,
  ] {
    def fromThrift(
      thrift: thriftjava.VirtualizedExecuteProcessRequest,
    ): Try[VirtualizedExecuteProcessRequest] = Try {
      val conjoinedRequests = thrift.getConjoined_requests.asScala
      val daemonExecutionRequest = Option(thrift.getDaemon_execution_request)
      VirtualizedExecuteProcessRequest(
        conjoinedRequests = conjoinedRequests.map(_.fromThrift().get),
        daemonExecutionRequest = daemonExecutionRequest.map(_.fromThrift().get),
      )
    }
    def toThrift(
      self: VirtualizedExecuteProcessRequest,
    ): thriftjava.VirtualizedExecuteProcessRequest = {
      val VirtualizedExecuteProcessRequest(conjoinedRequests, daemonExecutionRequest) = self
      val ret = new thriftjava.VirtualizedExecuteProcessRequest
      ret.setConjoined_requests(conjoinedRequests.map(_.toThrift).asJava)
      daemonExecutionRequest.foreach(req => ret.setDaemon_execution_request(req.toThrift))
      ret
    }
  }

  implicit object ProcessResultViaThrift extends ViaThrift[
    thriftjava.ExecuteProcessResult,
    ExecuteProcessResult,
  ] {
    def fromThrift(
      thrift: thriftjava.ExecuteProcessResult,
    ): Try[ExecuteProcessResult] = Try {
      val exitCode = thrift.getExit_code
      val stdout = thrift.getStdout
      val stderr = thrift.getStderr
      val digest = thrift.getOutput_directory_digest
      ExecuteProcessResult(
        exitCode = ExitCode(exitCode),
        ioState = IOFinalState(
          vfsDigest = digest.fromThrift().get,
          stdout = stdout.fromThrift().get,
          stderr = stderr.fromThrift().get,
        ),
      )
    }
    def toThrift(self: ExecuteProcessResult): thriftjava.ExecuteProcessResult = {
      val ExecuteProcessResult(
        ExitCode(exitCode),
        IOFinalState(digest, stdout, stderr),
      ) = self
      val ret = new thriftjava.ExecuteProcessResult
      ret.setExit_code(exitCode)
      ret.setStdout(stdout.toThrift)
      ret.setStderr(stderr.toThrift)
      ret.setOutput_directory_digest(digest.toThrift)
      ret
    }
  }

  implicit object SubprocessRequestIdViaThrift
      extends ViaThrift[thriftjava.SubprocessRequestId, SubprocessRequestId] {
    def fromThrift(thrift: thriftjava.SubprocessRequestId): Try[SubprocessRequestId] = Try {
      val id = thrift.getId
      SubprocessRequestId(id)
    }
    def toThrift(self: SubprocessRequestId): thriftjava.SubprocessRequestId = {
      val SubprocessRequestId(id) = self
      val ret = new thriftjava.SubprocessRequestId
      ret.setId(id)
      ret
    }
  }

  implicit object ProcessReapResultViaThrift
      extends ViaThrift[thriftjava.ProcessReapResult, ProcessReapResult] {
    def fromThrift(thrift: thriftjava.ProcessReapResult): Try[ProcessReapResult] = Try {
      val exeResult = thrift.getExe_result
      val id = thrift.getId
      ProcessReapResult(
        exeResult = exeResult.fromThrift().get,
        id = id.fromThrift().get
      )
    }
    def toThrift(self: ProcessReapResult): thriftjava.ProcessReapResult = {
      val ProcessReapResult(exeResult, id) = self
      val ret = new thriftjava.ProcessReapResult
      ret.setExe_result(exeResult.toThrift)
      ret.setId(id.toThrift)
      ret
    }
  }
}
