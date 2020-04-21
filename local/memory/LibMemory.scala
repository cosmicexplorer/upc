package upc.local.memory

import jnr.ffi._


object LibMemory {
  import LibMemoryEnums._

  trait Iface {
    def shm_get_key(request: ShmGetKeyRequest, result: ShmKey): Unit
    def shm_allocate(request: Pointer, result: Pointer): Unit
    def shm_retrieve(request: ShmRetrieveRequest, result: ShmRetrieveResult): Unit
    def shm_delete(request: ShmDeleteRequest, result: ShmDeleteResult): Unit
    def shm_free_error_message(error_message: Pointer): Unit
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

    val size_bytes = new Unsigned64
    // FIXME: This used to be a separate Fingerprint struct, like in the actual FFI, but it caused
    // errors -- the equivalent of this array kept being zeroed out. Since a struct that has only
    // one element can be replaced precisely with that element in memory, we still have a 1:1
    // mapping.
    val fingerprint = array(new Array[Unsigned8](FINGERPRINT_LENGTH))

    def getSize: Long = size_bytes.get

    def getFingerprintBytes: Array[Byte] = fingerprint.map(u8 => u8.get.toByte)

    def copyKeyFrom(other: ShmKey): Unit = {
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
    val size = new Unsigned64
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
  class ShmAllocateRequest(runtime: Runtime = runtime) extends ShmKey(runtime) {
    val source = new Pointer
  }
  object ShmAllocateRequest {
    def apply(key: ShmKey, source: Pointer): ShmAllocateRequest = {
      val ret = new ShmAllocateRequest
      ret.copyKeyFrom(key)
      ret.source.set(source)
      ret
    }
  }
  // [Result] for shm_allocate()
  class ShmAllocateResult(runtime: Runtime = runtime) extends Struct(runtime) {
    import ShmKey._
    val address = new Pointer
    val error_message = new Pointer
    val correct_size_bytes = new Unsigned64
    val correct_fingerprint = array(new Array[Unsigned8](FINGERPRINT_LENGTH))
    val status: Enum[ShmAllocateResultStatus_Tag] = new Enum(classOf[ShmAllocateResultStatus_Tag])

    lazy val getCorrectSize: Long = correct_size_bytes.get
    lazy val getCorrectFingerprintBytes: Array[Byte] = correct_fingerprint.map(u8 => u8.get.toByte)

    lazy val correctKey: ShmKey = ShmKey(getCorrectSize, getCorrectFingerprintBytes)
  }

  // [Request] for shm_retrieve()
  class ShmRetrieveRequest(runtime: Runtime = runtime) extends ShmKey(runtime)
  object ShmRetrieveRequest {
    def apply(key: ShmKey): ShmRetrieveRequest = {
      val ret = new ShmRetrieveRequest
      ret.copyKeyFrom(key)
      ret
    }
  }
  // [Result] for shm_retrieve()
  class ShmRetrieveResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val status: Enum[ShmRetrieveResultStatus_Tag] = new Enum(classOf[ShmRetrieveResultStatus_Tag])
    val address = new Pointer
    val error = new Pointer
  }

  // [Request] for shm_delete()
  class ShmDeleteRequest(runtime: Runtime = runtime) extends Struct(runtime) {
    val key = new ShmKey
  }
  object ShmDeleteRequest {
    def apply(key: ShmKey): ShmDeleteRequest = {
      val ret = new ShmDeleteRequest
      ret.key.copyKeyFrom(key)
      ret
    }
  }
  // [Result] for shm_delete()
  class ShmDeleteResult(runtime: Runtime = runtime) extends Struct(runtime) {
    val status: Enum[ShmDeleteResultStatus_Tag] = new Enum(classOf[ShmDeleteResultStatus_Tag])
    val error = new Pointer
  }
}
