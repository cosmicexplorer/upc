package upc.local.virtual_cli

import upc.local.thrift_java.process_execution.{ExecuteProcessResult, ProcessReapService}
import upc.local.virtual_cli.directory.DirectoryDigest

import ammonite.ops._
import com.martiansoftware.nailgun.NGContext
import org.apache.thrift.protocol.TBinaryProtocol

import java.io.{PrintStream => JavaPrintStream, IOException}
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try


sealed abstract class VirtualIOError(message: String) extends IOException(message)
case class ThriftClientAcquisitionFailure(message: String) extends VirtualIOError(message)


class VFSImpl(val fileMapping: FileMapping) extends DescriptorTrackingVFS(fileMapping)
    with VFSImplicits


trait Exitable[StatusType] {
  def exit(exitCode: StatusType): Future[Unit]
}


class VirtualIOVViaThriftServices(val ioServices: IOServices, vfs: VFSImpl)
    extends Exitable[ExitCode] {
  import ioServices.executor

  object Implicits {
    import vfs.VFSImplicits._
  }

  override def exit(exitCode: ExitCode): Future[Unit] =
    ioServices.reapProcess(exitCode, vfs.currentFileMapping)
      .flatMap(() => ioServices.asyncClose())
}

object VirtualIOVViaThriftServices {
  def apply(config: IOServicesConfig): Future[VirtualIOVViaThriftServices] = {
    import config.executor

    val ioServices = new IOServices(config)
    val vfs = ioServices.readFileMapping().map(new VFSImpl(_))
    vfs.map(new VirtualIOVViaThriftServices(ioServices, _))
  }
}

// The reverse of Exitable, kinda.
trait Setupable[RequestType] {
  def setUp(request: RequestType): Future[Unit]
}

object VirtualIOLayer extends Setupable[IOServicesConfig] with Exitable[ExitCode] {
  implicit var executor: ExecutionContext = null
  def initializeExecutor(otherExecutor: ExecutionContext): Unit = {
    executor = otherExecutor
  }

  var thriftSyncedIOLayer: VirtualIOVViaThriftServices = null
  def initializeThriftSyncedIOLayer(otherThriftSyncedIOLayer: VirtualIOVViaThriftServices): Unit = {
    thriftSyncedIOLayer = otherThriftSyncedIOLayer
  }

  object VFSImplicits {
    import thriftSyncedIOLayer.Implicits._
  }

  override def setUp(config: IOServicesConfig): Future[Unit] = {
    initializeExecutor(config.executor)

    VirtualIOVViaThriftServices(config)
      .map(initializeThriftSyncedIOLayer(_))
  }

  override def exit(exitCode: ExitCode): Future[Unit] = thriftSyncedIOLayer.exit(exitCode)
}



trait VirtualizationImplementation {
  import VirtualIOLayer.executor

  def virtualizedMainMethod(args: Array[String], cwd: Path): Int

  def acquireIOServicesConfig(): Future[IOServicesConfig]

  def processResult(runMainMethod: => Int): Future[ExitCode] = {
    val setupVirtualIO: Future[Unit] = acquireIOServicesConfig().flatMap(VirtualIOLayer.setUp(_))

    val doRunMain: Future[ExitCode] = setupVirtualIO.flatMap(Future { blocking { runMainMethod } })
      .map(ExitCode(_))

    doRunMain
      .flatMap(exitCode => VirtualIOLayer.exit(exitCode).map(() => exitCode))
  }

  def mainWrapper(args: Array[String], cwd: Path): Future[Int] = {
    processResult { virtualizedMainMethod(args, cwd) }
      .map(_.code)
  }
}


trait MainWrapper extends VirtualizationImplementation {
  val MEMORY_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR: String
  val DIRECTORY_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR: String
  val PROCESS_REAP_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR: String

  def extractEnvVarPath(envVar: String): Try[Path] = Try {
    sys.env.get(envVar) match {
      case None => throw ThriftClientAcquisitionFailure(s"env var $envVar was not set!!"),
      case Some(pathStr) => {
        val path = Path(pathStr)
        if (!path.exists) {
          throw ThriftClientAcquisitionFailure(s"path $path from env var $envVar does not exist!!!")
        } else {
          path
        }
      }
    }
  }

  def createExecutor

  override def acquireIOServicesConfig(): Future[IOServicesConfig] = {
    val getMemorySocketPath = extractEnvVarPath(MEMORY_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR)
    val getDirectorySocketPath = extractEnvVarPath(DIRECTORY_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR)
    val getProcessReapSocketPath = extractEnvVarPath(PROCESS_REAP_SERVICE_THRIFT_SOCKET_LOCATION_ENV_VAR)
    Future.sequence((getMemorySocketPath, getDirectorySocketPath, getProcessReapSocketPath))
      .map { case (memoryPath, directoryPath, processReapPath) => IOServicesConfig(
        memoryService = memoryPath,
        directoryService = directoryPath,
        processReapService = processReapPath,
      )}
  }

  def main(args: Array[String]): Unit = {
    val workingDirectory = java.nio.file.Paths.get(".").toAbsolutePath
    val ExitCode(exitCode) = mainWrapper(args, Path(workingDirectory)).get
    sys.exit(exitCode)
  }

  def nailMain(context: NGContext): Unit = {
    val workingDirectory = context.getWorkingDirectory
    val ExitCode(exitCode) = mainWrapper(context.getArgs, Path(workingDirectory)).get
    context.exit(exitCode)
  }
}
