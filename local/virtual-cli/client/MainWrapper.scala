package upc.local.virtual_cli.client

import upc.local.ExitCode
import upc.local.directory
import upc.local.memory
import upc.local.thrift_java.process_execution.{ExecuteProcessResult, ProcessReapService}

import ammonite.ops._
import com.martiansoftware.nailgun.NGContext
import org.apache.thrift.protocol.TBinaryProtocol

import java.io.{PrintStream => JavaPrintStream, IOException, File => JavaFile}
import java.util.concurrent.Executors
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

import scala.util.{Try, Success, Failure}


sealed abstract class VirtualIOError(message: String, cause: Throwable = null)
    extends IOException(message, cause)
case class ThriftClientAcquisitionFailure(message: String) extends VirtualIOError(message)
case class IOInitializationError(message: String) extends VirtualIOError(message)
case class ExecutionContextCreationError(message: String, cause: Throwable = null)
    extends VirtualIOError(message, cause)
case class LazyRefCellBadAcquisitionState(message: String) extends VirtualIOError(message)


class VFSImpl(val fileMapping: FileMapping) extends DescriptorTrackingVFS(fileMapping)
    with VFSImplicits


trait Exitable[StatusType] {
  def exit(exitCode: StatusType): Future[Unit]
}


case class VirtualizedProcessExit(
  exitCode: ExitCode,
  stdioResults: StdioResults,
)


class VirtualIOVViaThriftServices(ioServices: IOServices, val vfs: VFSImpl)
    extends Exitable[VirtualizedProcessExit] {
  import ioServices.executor

  val Implicits = vfs.VFSImplicitDefs

  override def exit(res: VirtualizedProcessExit): Future[Unit] = {
    val VirtualizedProcessExit(exitCode, stdioResults) = res
    ioServices.reapProcess(exitCode, stdioResults, vfs.currentFileMapping)
      .flatMap(Unit => ioServices.asyncClose())
  }
}

object VirtualIOVViaThriftServices {
  def apply(config: IOServicesConfig): Future[VirtualIOVViaThriftServices] = {
    import config.executor

    val ioServices = new IOServices(config)
    val vfs = ioServices.readFileMapping().map(new VFSImpl(_))
    vfs.map(new VirtualIOVViaThriftServices(ioServices, _))
  }
}

// We need this class in order to declare a "stable reference" (i.e. a val or lazy val, *not* a def
// or var) to import its implicits in VirtualIOLayer.
class LazyRefCell[T](var obj: Option[T]) {
  def set(o: T): Unit = {
    obj = Some(o)
  }

  lazy val stableObject: T = obj.getOrElse {
    throw LazyRefCellBadAcquisitionState(s"lazy ref cell $this for object $obj was not initialized!")
  }
}
object LazyRefCell {
  def empty[T] = new LazyRefCell[T](None)
}

// The reverse of Exitable, kinda.
trait Setupable[RequestType] {
  def setUp(request: RequestType): Future[Unit]
}

object VirtualIOLayer extends Setupable[IOServicesConfig] with Exitable[VirtualizedProcessExit] {
  private val executorCell: LazyRefCell[ExecutionContext] = LazyRefCell.empty
  private def initializeExecutor(otherExecutor: ExecutionContext): Unit = {
    executorCell.set(otherExecutor)
  }
  implicit lazy val executor: ExecutionContext = executorCell.stableObject

  private val thriftSyncedIOLayerCell: LazyRefCell[VirtualIOVViaThriftServices] = LazyRefCell.empty
  private def initializeThriftSyncedIOLayer(otherLayer: VirtualIOVViaThriftServices): Unit = {
    thriftSyncedIOLayerCell.set(otherLayer)
  }
  lazy val thriftSyncedIOLayer: VirtualIOVViaThriftServices = thriftSyncedIOLayerCell.stableObject
  lazy val Implicits = thriftSyncedIOLayer.Implicits

  lazy val stdout = InMemoryStdio.stdout
  lazy val stderr = InMemoryStdio.stderr

  override def setUp(config: IOServicesConfig): Future[Unit] = {
    initializeExecutor(config.executor)

    VirtualIOVViaThriftServices(config)
      .map(initializeThriftSyncedIOLayer(_))
  }

  override def exit(result: VirtualizedProcessExit): Future[Unit] = thriftSyncedIOLayer.exit(result)
}



trait VirtualizationImplementation {
  def virtualizedMainMethod(args: Array[String], cwd: Path): Int

  def acquireIOServicesConfig(): Try[IOServicesConfig]

  def withVirtualIOLayer(runMainMethod: => Int): ExitCode = {
    val ioConfig = acquireIOServicesConfig() match {
      case Success(x) => x
      case Failure(e) => throw e
    }
    VirtualIOLayer.setUp(ioConfig)
    // The executor is initialized by the .setUp() call, so we wait until here to import it.
    import VirtualIOLayer.executor

    // Acquire stdio.
    InMemoryStdio.acquire()

    val getExitCode: Future[ExitCode] = for {
      exitCode <- Future { blocking { runMainMethod } }.map(ExitCode(_))
      // Dump stdio.
      stdioResults = InMemoryStdio.collect()
      () <- VirtualIOLayer.exit(VirtualizedProcessExit(exitCode, stdioResults))
    } yield exitCode

    Await.result(getExitCode, Duration.Inf)
  }

  def mainWrapper(args: Array[String], cwd: Path): ExitCode = withVirtualIOLayer {
    virtualizedMainMethod(args, cwd)
  }
}


trait MainWrapper extends VirtualizationImplementation {
  val VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR = "UPC_VFS_FILE_MAPPING_FINGERPRINT"
  val VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR = "UPC_VFS_FILE_MAPPING_SIZE_BYTES"

  val PROCESS_REAP_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR = "UPC_PROCESS_REAP_SERVICE_THRIFT_SOCKET_PATH"

  val EXECUTOR_NUM_THREADS_ENV_VAR = "UPC_EXECUTOR_NUM_THREADS"

  def extractEnvVarPath(envVar: String): Try[Path] = Try {
    sys.env.get(envVar) match {
      case None => throw ThriftClientAcquisitionFailure(s"env var $envVar was not set!!")
      case Some(pathStr) => {
        val path = new JavaFile(pathStr)
        if (!path.exists) {
          throw ThriftClientAcquisitionFailure(s"path $path from env var $envVar does not exist!!!")
        } else {
          Path(path)
        }
      }
    }
  }

  def createExecutionContext(): Try[ExecutionContext] = Try {
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

  def getIOInitializationConfig(): Try[directory.DirectoryDigest] = Try {
    val fingerprintHex = sys.env(VFS_FILE_MAPPING_FINGERPRINT_ENV_VAR)
    val sizeBytes = sys.env(VFS_FILE_MAPPING_SIZE_BYTES_ENV_VAR).toLong
    memory.ShmKey.fromFingerprintHex(fingerprintHex, sizeBytes)
  }

  override def acquireIOServicesConfig(): Try[IOServicesConfig] = for {
    initDigest <- getIOInitializationConfig()
    processReapServicePath <- extractEnvVarPath(PROCESS_REAP_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR)
    executor <- createExecutionContext()
  } yield new IOServicesConfig(
    initialDigest = initDigest,
    processReapServicePath = processReapServicePath,
    executor = executor,
  )

  def main(args: Array[String]): Unit = {
    val workingDirectory = java.nio.file.Paths.get(".").toAbsolutePath
    val ExitCode(exitCode) = mainWrapper(args, Path(workingDirectory))
    sys.exit(exitCode)
  }

  def nailMain(context: NGContext): Unit = {
    val workingDirectory = context.getWorkingDirectory
    val ExitCode(exitCode) = mainWrapper(context.getArgs, Path(workingDirectory))
    context.exit(exitCode)
  }
}
