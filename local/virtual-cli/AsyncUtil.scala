package upc.local.virtual_cli

import java.io.{Closeable => JavaCloseable, IOException}
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{ExecutionContext, Future}


sealed abstract class AsyncError(message: String) extends IOException(message)
case class InvalidReentrantLockState(message: String) extends AsyncError(message)

// This class should be considered the more general case compared to SyncCloseable. As described
// below, SyncCloseable is specifically for things which cannot move between threads while closing
// their owned resources.
trait AsyncCloseable {
  import AsyncCloseable.Implicits._
  def asyncClose(): Future[Unit]
}
object AsyncCloseable {
  object Implicits {
    implicit class CloseableWrapper[T <: JavaCloseable](closeable: T)(
      implicit executor: ExecutionContext
    ) extends AsyncCloseable {
      override def asyncClose(): Future[Unit] = Future { blocking { t.close() } }
    }
  }
}

// This is for types like CloseableLock below, which may error in their syncClose() methods (and
// hence want to return a Try[Unit] instead of raising an exception). For a lock like CloseableLock,
// we don't want to have our code shuttling between different thread via an async executor, like in
// AsyncCloseable above.
trait SyncCloseable extends JavaCloseable {
  def syncClose(): Try[Unit]

  override def close(): Unit = syncClose().get
}


// case class CloseableLock(innerLock: ReentrantLock) extends SyncCloseable {
//   override def syncClose(): Try[Unit] = Try {
//     Option(innerLock.getOwner) match {
//       case None => ()
//       case Some(owningThread) => {
//         val curThread = Thread.currentThread
//         if (owningThread != curThread) {
//           throw InvalidReentrantLockState(
//             s"another thread $owningThread held this lock $this when attempting to close it from $curThread!!")
//         } else if (innerLock.isLocked) {
//           throw InvalidReentrantLockState(
//             s"did not expect inner lock $this to be locked by thread $curThread!!!")
//         } else { () }
//       }
//     }
//   }

//   def withLock[T](f: => T): T = try {
//     innerLock.lock()
//     f
//   } finally {
//     innerLock.unlock()
//   }

//   // Use a re-entrant lock to send a lock condition through a Future!
//   def withFutureLock[T](f: => Future[T])(implicit executor: ExecutionContext): Future[T] = {
//     // Lock.
//     innerLock.lock()

//     val createdFuture = try {
//       f
//     } catch {
//       case e => Future.failed(e)
//     }

//     createdFuture.onComplete { _ =>
//       // Unlock when the future is completed.
//       explicitLock.unlock()
//     }

//     createdFuture

//   }
// }

// object CloseableLock {
//   def newLock(): CloseableLock = ClosableLock(ReentrantLock())
// }
