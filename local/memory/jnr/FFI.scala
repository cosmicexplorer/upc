package upc.local.memory.jnr

import jnr.ffi._
import jnr.ffi.types._

import scala.util.{Try, Success, Failure}


trait IntoNative[JvmT, NativeT] {
  def intoNative(jvm: JvmT): Try[NativeT]
}
object IntoNative {
  implicit class JvmToNativeWrapper[JvmT, NativeT](jvm: JvmT)(
    implicit ctx: IntoNative[JvmT, NativeT]
  ) {
    def intoNative(): Try[NativeT] = ctx.intoNative(jvm)
  }
}

trait FromNative[JvmT, NativeT] {
  def fromNative(native: NativeT): Try[JvmT]
}
object FromNative {
  implicit class JvmFromNativeWrapper[JvmT, NativeT](native: NativeT)(
    implicit ctx: FromNative[JvmT, NativeT]
  ) {
    def fromNative(): Try[JvmT] = ctx.fromNative(native)
  }
}


trait FFI {
}


object LibMemory extends FFI {
  abstract class FFIError(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause)
  case class FingerprintError(message: String, cause: Throwable = null)
      extends FFIError(message, cause)
  case class ShmKeyError(message: String, cause: Throwable = null)
      extends FFIError(message, cause)

  lazy val instance: Iface = {
    val lib_path = "/Users/dmcclanahan/projects/active/upc/local/target/debug"
    val loader = LibraryLoader.create(classOf[Iface])
    lib_path.split(":").foreach(loader.search(_))
    loader.load("memory")
  }
  lazy val runtime = Runtime.getRuntime(instance)

  trait Iface {
    def shm_get_key(request: ShmGetKeyRequest): ShmKey
  }

  object ShmKey {
    val FINGERPRINT_LENGTH: Int = 32
  }
  class ShmKey(runtime: Runtime = runtime) extends Struct(runtime) {
    import ShmKey._

    val size_bytes = new Unsigned32
    val fingerprint = array(new Array[Unsigned8](FINGERPRINT_LENGTH))

    def getLength: Long = size_bytes.get

    def setLength(other: Long): Unit = {
      if (other < 0) {
        throw ShmKeyError(s"length cannot be negative -- was $other")
      }
      size_bytes.set(other)
    }

    def getBytes: Array[Byte] = fingerprint.map(u8 => u8.get.toByte)
  }

  class ShmGetKeyRequest(runtime: Runtime = runtime) extends Struct(runtime) {
    val size = new Unsigned32
    val source = new Pointer
  }
  object ShmGetKeyRequest {
    def apply(sourceArg: Pointer): ShmGetKeyRequest = {
      val ret = new ShmGetKeyRequest
      ret.size.set(sourceArg.size: Long)
      ret.source.set(sourceArg)
      ret
    }
  }
}

// class JNRFFI extends Struct with FFI
