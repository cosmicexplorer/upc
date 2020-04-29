package upc.local.virtual_cli.client

import upc.local._
import upc.local.thrift_socket.ThriftUnixClient
import upc.local.thrift.ViaThrift._
import upc.local.thriftjava.{process_execution => thriftjava}

import ammonite.ops._
import com.martiansoftware.nailgun.NGContext
import org.apache.thrift.protocol.TBinaryProtocol

import java.util.concurrent.Executors
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try


trait VirtualizationImplementation {
  def virtualizedMainMethod(args: Array[String]): Int

  def acquireIOServicesConfig(): Try[IOServicesConfig]

  lazy val ioLayer: VirtualIOLayer = new VirtualIOLayer

  def acquireStdio(): InMemoryStdio = InMemoryStdio.acquire(overwriteStderr = false)

  def withVirtualIOLayer(cwd: Path)(
    runMainMethod: => Int
  ): Future[ExecuteProcessResult] = {
    // Acquire stdio.
    val inMemStdio = acquireStdio()

    val ioConfig = acquireIOServicesConfig().get
    implicit val executor = ioConfig.executor

    for {
      () <- ioLayer.setUp(IOLayerConfig(cwd, ioConfig))
      exitCode <- Future { blocking { runMainMethod } }.map(ExitCode(_))
      // Dump stdio.
      stdioResults = inMemStdio.collect()
      result <- ioLayer.exit(VirtualizedProcessExit(exitCode, stdioResults))
    } yield result
  }

  def mainWrapper(args: Array[String], cwd: Path): Future[ExecuteProcessResult] =
    withVirtualIOLayer(cwd) { virtualizedMainMethod(args) }
}


object MainWrapperEnvVars {
  val VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR = "UPC_VFS_FILE_MAPPING_FINGERPRINT"
  val VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR = "UPC_VFS_FILE_MAPPING_SIZE_BYTES"

  val PROCESS_REAP_SERVICE_THRIFT_SOCKET_PATH_ENV_VAR = "UPC_PROCESS_REAP_SERVICE_THRIFT_SOCKET_PATH"

  val SUBPROCESS_REQUEST_ID_ENV_VAR = "UPC_SUBPROCESS_REQUEST_ID"

  val EXECUTOR_NUM_THREADS_ENV_VAR = "UPC_EXECUTOR_NUM_THREADS"
}

trait MainWrapper extends VirtualizationImplementation {
  import MainWrapperEnvVars._

  override lazy val ioLayer: VirtualIOLayer = VirtualIOLayer

  implicit lazy val executor: ExecutionContext = {
    val numThreads: Int = sys.env.get(EXECUTOR_NUM_THREADS_ENV_VAR) match {
      case None => 6
      case Some(numStr) => try {
        numStr.toInt
      } catch {
        case e: Throwable => throw ExecutionContextCreationError(
          message = s"failed to parse number of threads $numStr",
          cause = e)
      }
    }
    val pool = Executors.newFixedThreadPool(numThreads)
    ExecutionContext.fromExecutorService(pool)
  }

  lazy val initialDigest: DirectoryDigest = {
    val fingerprintHex = sys.env(VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR)
    val sizeBytes = sys.env(VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR).toLong
    val digest = Digest.fromFingerprintHex(fingerprintHex, sizeBytes).get
    DirectoryDigest(digest)
  }

  override def acquireIOServicesConfig(): Try[IOServicesConfig] = Try(
    IOServicesConfig(
      initialDigest = initialDigest,
      executor = executor,
    ))

  def withProcessReapClient(f: => Future[ExecuteProcessResult]): Try[ExitCode] = Try {
    val subprocessRequestId = SubprocessRequestId(sys.env(SUBPROCESS_REQUEST_ID_ENV_VAR))
    val processReapServicePath = Path(sys.env(PROCESS_REAP_SERVICE_THRIFT_SOCKET_PATH_ENV_VAR))
    val socket = ThriftUnixClient(processReapServicePath).get
    val protocol = new TBinaryProtocol(socket)
    val client = new thriftjava.ProcessReapService.Client(protocol)

    val result: ExecuteProcessResult = Await.result(f, Duration.Inf)
    val reapResult = ProcessReapResult(exeResult = result, id = subprocessRequestId)

    val () = client.reapProcess(reapResult.toThrift)
    result.exitCode
  }

  def main(args: Array[String]): Unit = {
    val workingDirectory = java.nio.file.Paths.get(".").toAbsolutePath
    val ExitCode(exitCode) = withProcessReapClient {
      mainWrapper(args, Path(workingDirectory))
    }.get
    sys.exit(exitCode)
  }

  def nailMain(context: NGContext): Unit = {
    val workingDirectory = context.getWorkingDirectory
    val ExitCode(exitCode) = withProcessReapClient {
      mainWrapper(context.getArgs, Path(workingDirectory))
    }.get
    context.exit(exitCode)
  }
}
