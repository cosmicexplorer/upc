package upc.local.virtual_cli.util

import java.io.{Closeable => JavaCloseable}
import scala.concurrent.{blocking, ExecutionContext, Future}


// This class should be considered the more general case compared to SyncCloseable. As described
// below, SyncCloseable is specifically for things which cannot move between threads while closing
// their owned resources.
trait AsyncCloseable {
  def asyncClose(): Future[Unit]
}
object AsyncCloseable {
  object Implicits {
    implicit class CloseableWrapper[T <: JavaCloseable](closeable: T)(
      implicit executor: ExecutionContext
    ) extends AsyncCloseable {
      override def asyncClose(): Future[Unit] = Future { blocking { closeable.close() } }
    }
  }
}
