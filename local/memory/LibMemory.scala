package upc.local.memory

import jnr.ffi._


object LibMemory {
  import LibMemoryEnums._

  trait Iface {
    def shm_get_key(request: ShmGetKeyRequest, result: ShmKey): Unit
    def shm_allocate(request: ShmAllocateRequest, result: ShmAllocateResult): Unit
    def shm_retrieve(request: ShmRetrieveRequest, result: ShmRetrieveResult): Unit
    def shm_delete(request: ShmDeleteRequest, result: ShmDeleteResult): Unit
    def shm_free_error_message(error_message: Pointer): Unit
  }

  abstract class FFIError(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause)

  implicit lazy val instance: Iface = {
    val lib_path = "/Users/dmcclanahan/projects/active/upc/local/target/debug"
    val loader = LibraryLoader.create(classOf[Iface])
    lib_path.split(":").foreach(loader.search(_))
    loader.load("memory")
  }
  implicit lazy val runtime = Runtime.getRuntime(instance)

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

    lazy val getFingerprintBytes: Array[Byte] = fingerprint.map(u8 => u8.get.toByte)

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

  class SharedMemAddressOrErrorMessage(runtime: Runtime = runtime) extends ShmKey(runtime) {
    val address = new Pointer
    val error_message = new Pointer

    // Copy out the memory from the pointer to a directly allocated buffer.
    lazy val getAddressPointer: jnr.ffi.Pointer = {
      val n = getSize.toInt
      val intermediateArray: Array[Byte] = new Array(n)
      address.get.get(0, intermediateArray, 0, n)
      // FIXME: Why do we have to explicitly allocate new direct memory to avoid segfaults? Why
      // can't we just use a pointer directly to that memory (the pointer returned by calling the
      // FFI method)?
      intoDirectPointer(intermediateArray)
    }
  }

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
  class ShmAllocateResult(runtime: Runtime = runtime)
      extends SharedMemAddressOrErrorMessage(runtime) {
    val status: Enum8[ShmAllocateResultStatus_Tag] = new Enum8(classOf[ShmAllocateResultStatus_Tag])
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
  class ShmRetrieveResult(runtime: Runtime = runtime)
      extends SharedMemAddressOrErrorMessage(runtime) {
    val status: Enum8[ShmRetrieveResultStatus_Tag] = new Enum8(classOf[ShmRetrieveResultStatus_Tag])
  }

  // [Request] for shm_delete()
  class ShmDeleteRequest(runtime: Runtime = runtime) extends ShmKey(runtime)
  object ShmDeleteRequest {
    def apply(key: ShmKey): ShmDeleteRequest = {
      val ret = new ShmDeleteRequest
      ret.copyKeyFrom(key)
      ret
    }
  }
  // [Result] for shm_delete()
  class ShmDeleteResult(runtime: Runtime = runtime) extends ShmKey(runtime) {
    val error = new Pointer
    val status: Enum8[ShmDeleteResultStatus_Tag] = new Enum8(classOf[ShmDeleteResultStatus_Tag])
  }
}
