package upc.local.virtual_cli.client

import upc.local._
import upc.local.thrift.ViaThrift._
import upc.local.thriftscala.{process_execution => thriftscala}

import ammonite.ops._
import com.martiansoftware.nailgun.NGContext
import com.twitter.finagle.Thrift

import java.util.concurrent.Executors
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try


trait VirtualizationImplementation {
  def virtualizedMainMethod(args: Array[String]): Int

  def acquireIOServicesConfig(): Try[IOServicesConfig]

  lazy val ioLayer: VirtualIOLayer = new VirtualIOLayer

  def withVirtualIOLayer(cwd: Path)(
    runMainMethod: => Int
  ): Future[CompleteVirtualizedProcessResult] = {
    // Acquire stdio.
    val inMemStdio = InMemoryStdio.acquire()

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

  def mainWrapper(args: Array[String], cwd: Path): Future[CompleteVirtualizedProcessResult] =
    withVirtualIOLayer(cwd) { virtualizedMainMethod(args) }
}


trait MainWrapper extends VirtualizationImplementation {
  val VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR = "UPC_VFS_FILE_MAPPING_FINGERPRINT"
  val VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR = "UPC_VFS_FILE_MAPPING_SIZE_BYTES"

  val PROCESS_REAP_SERVICE_THRIFT_SOCKET_PORT_ENV_VAR = "UPC_PROCESS_REAP_SERVICE_THRIFT_SOCKET_PORT"

  val EXECUTOR_NUM_THREADS_ENV_VAR = "UPC_EXECUTOR_NUM_THREADS"

  override lazy val ioLayer: VirtualIOLayer = VirtualIOLayer

  implicit lazy val executor: ExecutionContext = {
    val numThreads: Int = sys.env.get(EXECUTOR_NUM_THREADS_ENV_VAR) match {
      case None => throw ExecutionContextCreationError(
        s"env var $EXECUTOR_NUM_THREADS_ENV_VAR was not set!!!")
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

  def withProcessReapClient(f: => Future[CompleteVirtualizedProcessResult]): Try[ExitCode] = Try {
    val processReapServicePort = sys.env(PROCESS_REAP_SERVICE_THRIFT_SOCKET_PORT_ENV_VAR).toInt
    val client = Thrift.Client().build[
      thriftscala.ProcessExecutionService[Future]
    ](processReapServicePort.toString)

    val result = Await.result(f, Duration.Inf)
    val () = Await.result(client.reapProcess(result.toThrift), Duration.Inf)
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
