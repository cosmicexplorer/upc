package upc.local.memory

import jnr.ffi._
import jnr.ffi.types._


object LibMemory {
  import LibMemoryEnums._

  trait Iface {
    def shm_get_key(request: ShmGetKeyRequest, result: ShmKey): Unit
    def shm_allocate(request: ShmAllocateRequest, result: ShmAllocateResult): Unit
    def shm_retrieve(request: ShmRetrieveRequest, result: ShmRetrieveResult): Unit
    def shm_delete(request: ShmDeleteRequest, result: ShmDeleteResult): Unit
  }

  abstract class FFIError(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause)

  lazy val instance: Iface = {
    val lib_path = "/Users/dmcclanahan/projects/active/upc/local/target/debug"
    val loader = LibraryLoader.create(classOf[Iface])
    lib_path.split(":").foreach(loader.search(_))
    loader.load("memory")
  }
  lazy val runtime = Runtime.getRuntime(instance)

  private[upc] def intoDirectPointer(bytes: Array[Byte]): Pointer = {
    val ptr = Memory.allocateDirect(runtime, bytes.length)
    ptr.put(0, bytes, 0, bytes.length)
    ptr
  }

  // Used in almost every request object.
  class ShmKey(runtime: Runtime = runtime) extends Struct(runtime) {
    import ShmKey._

    val size_bytes = new Unsigned32
    // FIXME: This used to be a separate Fingerprint struct, like in the actual FFI, but it caused
    // errors -- the equivalent of this array kept being zeroed out. Since a struct that has only
    // one element can be replaced precisely with that element in memory, we still have a 1:1
    // mapping.
    val fingerprint = array(new Array[Unsigned8](FINGERPRINT_LENGTH))

    def getSize: Long = size_bytes.longValue

    def getFingerprintBytes: Array[Byte] = fingerprint.map(u8 => u8.get.toByte)

    def copyFrom(other: ShmKey): Unit = {
      size_bytes.set(other.getSize)
      fingerprint.zip(other.fingerprint).foreach { case (dst, src) =>
        dst.set(src.get)
      }
    }
  }
  object ShmKey {
    val FINGERPRINT_LENGTH: Int = 32

    def apply(sizeBytes: Long, fingerprint: Array[Byte]): ShmKey = {
      val ret = new ShmKey
      ret.size_bytes.set(sizeBytes)
      ret.fingerprint.zip(fingerprint).foreach { case (dst, src) =>
        dst.set(src)
      }
      ret
    }
  }
  case class ShmKeyError(message: String, cause: Throwable = null)
      extends FFIError(message, cause)

  // [Request] for shm_get_key()
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

  // [Request] for shm_allocate()
  class ShmAllocateRequest(runtime: Runtime = runtime) extends Struct(runtime) {
    val key = new ShmKey
    val source = new Pointer
  }
  object ShmAllocateRequest {
    def apply(key: ShmKey, source: Pointer): ShmAllocateRequest = {
      val ret = new ShmAllocateRequest
      ret.key.copyFrom(key)
      ret.source.set(source)
      ret
    }
  }
  // [Result] for shm_allocate()
  class ShmAllocateResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val status: Enum[ShmAllocateResultStatus_Tag] = new Enum(classOf[ShmAllocateResultStatus_Tag])
    val correct_key = new ShmKey
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    // val body = new ShmAllocateResult_Body
    val address = new Pointer
    val error = new Pointer
  }

  // [Request] for shm_retrieve()
  class ShmRetrieveRequest(runtime: Runtime = runtime) extends Struct(runtime) {
    val key = new ShmKey
  }
  object ShmRetrieveRequest {
    def apply(key: ShmKey): ShmRetrieveRequest = {
      val ret = new ShmRetrieveRequest
      ret.key.copyFrom(key)
      ret
    }
  }
  // [Result]* for shm_retrieve()
  class RetrieveSucceeded_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class RetrieveInternalError_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class ShmRetrieveResult_Body(runtime: Runtime = runtime) extends Union(runtime) {
    val retrieve_succeeded = new RetrieveSucceeded_Body
    val retrieve_internal_error = new RetrieveInternalError_Body
  }
  // End [Result] for shm_retrieve()
  class ShmRetrieveResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val tag: Enum[ShmRetrieveResult_Tag] = new Enum(classOf[ShmRetrieveResult_Tag])
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    val body = new ShmRetrieveResult_Body
  }

  // [Request] for shm_delete()
  class ShmDeleteRequest(runtime: Runtime = runtime) extends Struct(runtime) {
    val key = new ShmKey
  }
  object ShmDeleteRequest {
    def apply(key: ShmKey): ShmDeleteRequest = {
      val ret = new ShmDeleteRequest
      ret.key.copyFrom(key)
      ret
    }
  }
  // [Result]* for shm_delete()
  class DeleteSucceeded_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class DeleteInternalError_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class ShmDeleteResult_Body(runtime: Runtime = runtime) extends Union(runtime) {
    val delete_internal_error = new DeleteInternalError_Body
  }
  // End [Result] for shm_delete()
  class ShmDeleteResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val tag: Enum[ShmDeleteResult_Tag] = new Enum(classOf[ShmDeleteResult_Tag])
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    val body = new ShmDeleteResult_Body
  }
}
