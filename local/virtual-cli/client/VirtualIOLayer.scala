package upc.local.virtual_cli.client

import upc.local._
import upc.local.directory
import upc.local.memory

import ammonite.ops._

import java.io.{PrintStream => JavaPrintStream, IOException, File => JavaFile}
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


trait Exitable[StatusType, T] {
  def exit(exitCode: StatusType): Future[T]
}


case class VirtualizedProcessExit(
  exitCode: ExitCode,
  stdioResults: StdioResults,
)


class VirtualIOAdaptor(ioServices: IOServices, val vfs: VFSImpl)
    extends Exitable[VirtualizedProcessExit, CompleteVirtualizedProcessResult] {
  import ioServices.executor

  val Implicits = vfs.VFSImplicitDefs

  override def exit(res: VirtualizedProcessExit): Future[CompleteVirtualizedProcessResult] = {
    val VirtualizedProcessExit(exitCode, stdioResults) = res
    ioServices.reapProcess(exitCode, stdioResults, vfs.currentFileMapping)
  }
}
object VirtualIOAdaptor {
  def apply(config: IOServicesConfig): Future[VirtualIOAdaptor] = {
    import config.executor

    val ioServices = new IOServices(config)
    val vfs = ioServices.readFileMapping().map(new VFSImpl(_))
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

class VirtualIOLayer
    extends Setupable[IOServicesConfig]
    with Exitable[VirtualizedProcessExit, CompleteVirtualizedProcessResult] {

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

  override def setUp(config: IOServicesConfig): Future[Unit] = {
    initializeExecutor(config.executor)

    VirtualIOAdaptor(config)
      .map(initializeVirtualIOAdaptor(_))
  }

  override def exit(result: VirtualizedProcessExit): Future[CompleteVirtualizedProcessResult] =
    virtualIOAdaptor.exit(result)
}
object VirtualIOLayer extends VirtualIOLayer
