package upc.local.virtual_cli.client

import upc.local._

import ammonite.ops._

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}


sealed abstract class VirtualIOError(message: String, cause: Throwable = null)
    extends IOException(message, cause)
case class ThriftClientAcquisitionFailure(message: String) extends VirtualIOError(message)
case class IOInitializationError(message: String) extends VirtualIOError(message)
case class ExecutionContextCreationError(message: String, cause: Throwable = null)
    extends VirtualIOError(message, cause)
case class LazyRefCellBadAcquisitionState(message: String) extends VirtualIOError(message)


class VFSImpl(cwd: Path, val fileMapping: FileMapping)
    extends DescriptorTrackingVFS(cwd, fileMapping)
    with VFSImplicits


trait Exitable[StatusType, T] {
  def exit(exitCode: StatusType): Future[T]
}


case class VirtualizedProcessExit(
  exitCode: ExitCode,
  stdioResults: StdioResults,
)


class VirtualIOAdaptor(ioServices: IOServices, val vfs: VFSImpl)
    extends Exitable[VirtualizedProcessExit, ExecuteProcessResult] {
  val Implicits = vfs.VFSImplicitDefs

  override def exit(res: VirtualizedProcessExit): Future[ExecuteProcessResult] = {
    val VirtualizedProcessExit(exitCode, stdioResults) = res
    ioServices.reapProcess(exitCode, stdioResults, vfs.currentFileMapping)
  }
}
object VirtualIOAdaptor {
  def apply(cwd: Path, config: IOServicesConfig): Future[VirtualIOAdaptor] = {
    implicit val executor = config.executor

    val ioServices = new IOServices(cwd, config)
    val vfs = ioServices.readFileMapping().map(new VFSImpl(cwd, _))
    vfs.map(new VirtualIOAdaptor(ioServices, _))
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

case class IOLayerConfig(
  cwd: Path,
  ioServices: IOServicesConfig,
)

class VirtualIOLayer
    extends Setupable[IOLayerConfig]
    with Exitable[VirtualizedProcessExit, ExecuteProcessResult] {

  private val executorCell: LazyRefCell[ExecutionContext] = LazyRefCell.empty
  private def initializeExecutor(otherExecutor: ExecutionContext): Unit = {
    executorCell.set(otherExecutor)
  }
  implicit lazy val executor: ExecutionContext = executorCell.stableObject

  private val virtualIOAdaptorCell: LazyRefCell[VirtualIOAdaptor] = LazyRefCell.empty
  private def initializeVirtualIOAdaptor(otherLayer: VirtualIOAdaptor): Unit = {
    virtualIOAdaptorCell.set(otherLayer)
  }
  lazy val virtualIOAdaptor: VirtualIOAdaptor = virtualIOAdaptorCell.stableObject
  lazy val Implicits = virtualIOAdaptor.Implicits

  override def setUp(ioLayerConfig: IOLayerConfig): Future[Unit] = {
    val IOLayerConfig(cwd, config) = ioLayerConfig

    initializeExecutor(config.executor)

    VirtualIOAdaptor(cwd, config)
      .map(initializeVirtualIOAdaptor(_))
  }

  override def exit(result: VirtualizedProcessExit): Future[ExecuteProcessResult] =
    virtualIOAdaptor.exit(result)
}
object VirtualIOLayer extends VirtualIOLayer
