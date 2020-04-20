package upc.local.memory

import jnr.ffi._
import jnr.ffi.types._


object LibMemory {
  import LibMemoryEnums._

  trait Iface {
    def shm_get_key(request: ShmGetKeyRequest): ShmKey
    // def shm_allocate(request: ShmAllocateRequest): ShmAllocateResult
    // def shm_retrieve(request: ShmRetrieveRequest): ShmRetrieveResult
    // def shm_delete(request: ShmDeleteRequest): ShmDeleteResult
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

  // Used in almost every request object.
  class ShmKey(runtime: Runtime = runtime) extends Struct(runtime) {
    import ShmKey._

    val size_bytes = new Unsigned32
    // FIXME: This used to be a separate Fingerprint struct, like in the actual FFI, but it caused
    // errors -- the equivalent of this array kept being zeroed out. Since a struct that has only
    // one element can be replaced precisely with that element in memory, we still have a 1:1
    // mapping.
    val fingerprint = array(new Array[Unsigned8](FINGERPRINT_LENGTH))

    def getSize: Long = size_bytes.get

    def getFingerprintBytes: Array[Byte] = fingerprint.map(u8 => u8.get.toByte)
  }
  object ShmKey {
    val FINGERPRINT_LENGTH: Int = 32
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
  // [Result] for shm_allocate()
  class AllocationSucceeded_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class DigestDidNotMatch_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new ShmKey
  }
  class AllocationFailed_Body(runtime: Runtime = runtime) extends Struct(runtime) {
    val _0 = new Pointer
  }
  class ShmAllocateResult_Body(runtime: Runtime = runtime) extends Union(runtime) {
    val allocation_succeeded = new AllocationSucceeded_Body
    val digest_did_not_match = new DigestDidNotMatch_Body
    val allocation_failed = new AllocationFailed_Body
  }
  // End [Result] for shm_allocate()
  class ShmAllocateResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val tag: Enum8[ShmAllocateResult_Tag] = new Enum8(classOf[ShmAllocateResult_Tag])
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    val body = new ShmAllocateResult_Body
  }
}
