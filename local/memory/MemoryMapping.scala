package upc.local.memory

import _root_.jnr.ffi.Pointer

import java.nio.ByteBuffer
import javax.xml.bind.DatatypeConverter
import scala.util.Try


sealed abstract class ShmError(message: String) extends Exception(message)
case class ShmNativeObjectEncodingError(message: String) extends ShmError(message)
class ShmAllocationError(message: String) extends ShmError(message)
class ShmRetrieveError(message: String) extends ShmError(message)
class ShmDeleteError(message: String) extends ShmError(message)
case class MemoryMappingReadError(message: String) extends ShmError(message)


object MemoryMapping {
  def fromArray(buf: Array[Byte]) = new MemoryMapping(LibMemory.intoDirectPointer(buf))
}

class MemoryMapping(private[upc] val pointer: Pointer) {
  def size: Long = pointer.size

  lazy val getBytes: Array[Byte] = {
    val bytes: Array[Byte] = new Array(pointer.size.toInt)
    pointer.get(0, bytes, 0, pointer.size.toInt)
    bytes
  }

  def readBytesAt(offset: Long, output: Array[Byte]): Int = {
    if (offset < 0) {
      throw MemoryMappingReadError(
        s"invalid offset $offset: cannot be negative!")
    }
    val remaining: Long = pointer.size - offset
    if (remaining < 0) {
      throw MemoryMappingReadError(
        s"invalid offset $offset greater than memory mapping size ${pointer.size}")
    }
    val toWrite: Int = Math.min(remaining.toInt, output.length)
    pointer.get(offset, output, 0, toWrite)
    toWrite
  }
}


case class ShmKey(fingerprint: Array[Byte], length: Long) {
  if (fingerprint.length != 32) {
    throw ShmNativeObjectEncodingError(s"invalid fingerprint: must be 32 bytes (was: $fingerprint)")
  }
  if (length < 0) {
    throw ShmNativeObjectEncodingError(s"invalid ShmKey: length must be non-negative (was: $this)")
  }

  lazy val fingerprintHex: String = DatatypeConverter.printHexBinary(fingerprint).toLowerCase

  override def toString: String = {
    s"ShmKey(length=$length, fingerprint=$fingerprintHex)"
  }
}

case class ShmGetKeyRequest(source: MemoryMapping)

case class ShmAllocateRequest(key: ShmKey, source: MemoryMapping)
sealed abstract class ShmAllocateResult
case class AllocationSucceeded(source: MemoryMapping) extends ShmAllocateResult
case class DigestDidNotMatch(key: ShmKey) extends ShmAllocationError(
  s"digest did not match: correct key was $key")
case class AllocationFailed(error: String) extends ShmAllocationError(
  s"allocation failed: $error")

case class ShmRetrieveRequest(key: ShmKey)
sealed abstract class ShmRetrieveResult
case class RetrieveSucceeded(source: MemoryMapping) extends ShmRetrieveResult
case object RetrieveDidNotExist extends ShmRetrieveError("entry to retrieve did not exist")
case class RetrieveInternalError(error: String) extends ShmRetrieveError(s"retrieve failed: $error")

case class ShmDeleteRequest(key: ShmKey)
sealed abstract class ShmDeleteResult
case object DeletionSucceeded extends ShmDeleteResult
case object DeleteDidNotExist extends ShmDeleteError("entry to delete did noot exist")
case class DeleteInternalError(error: String) extends ShmDeleteError(s"deletion failed: $error")


trait IntoNative[JvmType, NativeType] {
  def intoNative(jvm: JvmType): Try[NativeType]
}
object IntoNative {
  implicit class JvmToNativeWrapper[JvmType, NativeType](jvm: JvmType)(
    implicit ctx: IntoNative[JvmType, NativeType]
  ) {
    def intoNative(): Try[NativeType] = ctx.intoNative(jvm)
  }

  implicit object ShmKeyIntoNative extends IntoNative[ShmKey, LibMemory.ShmKey] {
    def intoNative(jvm: ShmKey): Try[LibMemory.ShmKey] = Try(
      LibMemory.ShmKey(jvm.length, jvm.fingerprint))
  }

  implicit object MemoryMappingIntoNative extends IntoNative[MemoryMapping, Pointer] {
    def intoNative(jvm: MemoryMapping): Try[Pointer] = Try(jvm.pointer)
  }

  implicit object ShmGetKeyRequestIntoNative
      extends IntoNative[ShmGetKeyRequest, LibMemory.ShmGetKeyRequest] {
    def intoNative(jvm: ShmGetKeyRequest): Try[LibMemory.ShmGetKeyRequest] = Try(
      LibMemory.ShmGetKeyRequest(jvm.source.intoNative().get))
  }

  implicit object ShmAllocateRequestIntoNative
      extends IntoNative[ShmAllocateRequest, LibMemory.ShmAllocateRequest] {
    def intoNative(jvm: ShmAllocateRequest): Try[LibMemory.ShmAllocateRequest] = Try(
      LibMemory.ShmAllocateRequest(
        jvm.key.intoNative().get,
        jvm.source.intoNative().get,
      ))
  }

  implicit object ShmRetrieveRequestIntoNative
      extends IntoNative[ShmRetrieveRequest, LibMemory.ShmRetrieveRequest] {
    def intoNative(jvm: ShmRetrieveRequest): Try[LibMemory.ShmRetrieveRequest] = Try(
      LibMemory.ShmRetrieveRequest(jvm.key.intoNative().get))
  }

  implicit object ShmDeleteRequestIntoNative
      extends IntoNative[ShmDeleteRequest, LibMemory.ShmDeleteRequest] {
    def intoNative(jvm: ShmDeleteRequest): Try[LibMemory.ShmDeleteRequest] = Try(
      LibMemory.ShmDeleteRequest(jvm.key.intoNative().get))
  }
}

trait FromNative[JvmType, NativeType] {
  // This one is a Try[_] because some native return values represent errors, which we convert to
  // exceptions!
  def fromNative(native: NativeType): Try[JvmType]
}
object FromNative {
  implicit class JvmFromNativeWrapper[JvmType, NativeType](native: NativeType)(
    implicit ctx: FromNative[JvmType, NativeType]
  ) {
    def fromNative(): Try[JvmType] = ctx.fromNative(native)
  }

  implicit object ShmKeyFromNative
      extends FromNative[ShmKey, LibMemory.ShmKey] {
    def fromNative(native: LibMemory.ShmKey): Try[ShmKey] = Try(ShmKey(
      fingerprint = native.getFingerprintBytes,
      length = native.getSize,
    ))
  }

  implicit object MemoryMappingFromNative
      extends FromNative[MemoryMapping, Pointer] {
    def fromNative(native: Pointer): Try[MemoryMapping] = Try(new MemoryMapping(native))
  }

  implicit object ShmAllocateResultFromNative
      extends FromNative[ShmAllocateResult, LibMemory.ShmAllocateResult] {
    def fromNative(native: LibMemory.ShmAllocateResult): Try[ShmAllocateResult] = Try {
      native.tag.get match {
        case LibMemoryEnums.ShmAllocateResult_Tag.AllocationSucceeded => AllocationSucceeded(
          native.body.allocation_succeeded._0.get.fromNative().get)
        case LibMemoryEnums.ShmAllocateResult_Tag.DigestDidNotMatch => throw DigestDidNotMatch(
          native.body.digest_did_not_match._0.fromNative().get)
        case LibMemoryEnums.ShmAllocateResult_Tag.AllocationFailed => throw AllocationFailed(
          native.body.allocation_failed._0.get.getString(0))
      }
    }
  }

  implicit object ShmRetrieveResultFromNative
      extends FromNative[ShmRetrieveResult, LibMemory.ShmRetrieveResult] {
    def fromNative(native: LibMemory.ShmRetrieveResult): Try[ShmRetrieveResult] = Try {
      native.tag.get match {
        case LibMemoryEnums.ShmRetrieveResult_Tag.RetrieveSucceeded => RetrieveSucceeded(
          native.body.retrieve_succeeded._0.get.fromNative().get)
        case LibMemoryEnums.ShmRetrieveResult_Tag.RetrieveDidNotExist => throw RetrieveDidNotExist
        case LibMemoryEnums.ShmRetrieveResult_Tag.RetrieveInternalError => throw RetrieveInternalError(
          native.body.retrieve_internal_error._0.get.getString(0))
      }
    }
  }

  implicit object ShmDeleteResultFromNative
      extends FromNative[ShmDeleteResult, LibMemory.ShmDeleteResult] {
    def fromNative(native: LibMemory.ShmDeleteResult): Try[ShmDeleteResult] = Try {
      native.tag.get match {
        case LibMemoryEnums.ShmDeleteResult_Tag.DeletionSucceeded => DeletionSucceeded
        case LibMemoryEnums.ShmDeleteResult_Tag.DeleteDidNotExist => throw DeleteDidNotExist
        case LibMemoryEnums.ShmDeleteResult_Tag.DeleteInternalError => throw DeleteInternalError(
          native.body.delete_internal_error._0.get.getString(0))
      }
    }
  }
}

object Shm {
  import IntoNative._
  import FromNative._

  import LibMemory.instance

  def getKey(request: ShmGetKeyRequest): Try[ShmKey] = Try {
    val req = request.intoNative().get
    // req.toString
    // throw new RuntimeException(req.toString)
    instance.shm_get_key(req).fromNative().get
  }

  def allocate(request: ShmAllocateRequest): Try[ShmAllocateResult] = Try(
    instance.shm_allocate(request.intoNative().get).fromNative().get)

  def retrieve(request: ShmRetrieveRequest): Try[ShmRetrieveResult] = Try(
    instance.shm_retrieve(request.intoNative().get).fromNative().get)

  def delete(request: ShmDeleteRequest): Try[ShmDeleteResult] = Try(
    instance.shm_delete(request.intoNative().get).fromNative().get)
}
