package upc.local.memory

import jnr.ffi._

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

class MemoryMapping(val pointer: Pointer) {
  def size: Long = pointer.size

  lazy val getBytes: Array[Byte] = {
    val bytes: Array[Byte] = new Array(pointer.size.toInt)
    pointer.get(0, bytes, 0, bytes.length)
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

  override def equals(other: Any): Boolean = {
    Option(other.asInstanceOf[ShmKey]) match {
      case None => false
      case Some(ShmKey(fp, len)) => fingerprint.zip(fp).map { case (fp1, fp2) => fp1 == fp2 }
          .fold(true) { case (acc, cur) => acc && cur }
    }
  }

  override def toString: String = {
    s"ShmKey(length=$length, fingerprint=$fingerprintHex)"
  }
}
object ShmKey {
  def fromFingerprintHex(hex: String, length: Long): Try[ShmKey] = Try(ShmKey(
    fingerprint = DatatypeConverter.parseHexBinary(hex),
    length = length))
}

case class ShmGetKeyRequest(source: MemoryMapping)

case class ShmAllocateRequest(key: ShmKey, source: MemoryMapping)

sealed abstract class ShmAllocateResult
case class AllocationSucceeded(key: ShmKey, source: MemoryMapping) extends ShmAllocateResult
case class DigestDidNotMatch(key: ShmKey) extends ShmAllocationError(
  s"digest did not match: correct key was $key")
case class AllocationFailed(key: ShmKey, error: String) extends ShmAllocationError(
  s"allocation failed for key $key: $error")

case class ShmRetrieveRequest(key: ShmKey)
sealed abstract class ShmRetrieveResult
case class RetrieveSucceeded(key: ShmKey, source: MemoryMapping) extends ShmRetrieveResult
case class RetrieveDidNotExist(key: ShmKey)
    extends ShmRetrieveError(s"entry for key $key did not exist!!")
case class RetrieveInternalError(key: ShmKey, error: String)
    extends ShmRetrieveError(s"retrieval of key $key failed: $error")

case class ShmDeleteRequest(key: ShmKey)
sealed abstract class ShmDeleteResult
case class DeletionSucceeded(key: ShmKey) extends ShmDeleteResult
case class DeleteDidNotExist(key: ShmKey)
    extends ShmDeleteError(s"entry to delete for key $key did not exist!")
case class DeleteInternalError(key: ShmKey, error: String)
    extends ShmDeleteError(s"deletion of key $key failed: $error")


trait IntoNative[JvmT, NativeT] {
  def intoNative(jvm: JvmT): Try[NativeT]
}
object IntoNative {
  implicit class JvmToNativeWrapper[JvmT, NativeT](jvm: JvmT)(
    implicit ctx: IntoNative[JvmT, NativeT]
  ) {
    def intoNative(): Try[NativeT] = ctx.intoNative(jvm)
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
    def intoNative(jvm: ShmAllocateRequest): Try[LibMemory.ShmAllocateRequest] = Try {
      LibMemory.ShmAllocateRequest(
        jvm.key.intoNative().get,
        jvm.source.intoNative().get,
      )
    }
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

trait FromNative[JvmT, NativeT] {
  // This one is a Try[_] because some native return values represent errors, which we convert to
  // exceptions!
  def fromNative(native: NativeT): Try[JvmT]
}
object FromNative {
  implicit class JvmFromNativeWrapper[JvmT, NativeT](native: NativeT)(
    implicit ctx: FromNative[JvmT, NativeT]
  ) {
    def fromNative(): Try[JvmT] = ctx.fromNative(native)
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
      val key = ShmKeyFromNative.fromNative(native).get
      native.status.get match {
        case LibMemoryEnums.ShmAllocateResultStatus_Tag.AllocationSucceeded => AllocationSucceeded(
          key,
          native.getAddressPointer.fromNative().get)
        case LibMemoryEnums.ShmAllocateResultStatus_Tag.DigestDidNotMatch => throw DigestDidNotMatch(key)
        case LibMemoryEnums.ShmAllocateResultStatus_Tag.AllocationFailed => throw AllocationFailed(
          key,
          native.error_message.get.getString(0))
      }
    }
  }

  implicit object ShmRetrieveResultFromNative
      extends FromNative[ShmRetrieveResult, LibMemory.ShmRetrieveResult] {
    def fromNative(native: LibMemory.ShmRetrieveResult): Try[ShmRetrieveResult] = Try {
      val key = ShmKeyFromNative.fromNative(native).get
      native.status.get match {
        case LibMemoryEnums.ShmRetrieveResultStatus_Tag.RetrieveSucceeded => RetrieveSucceeded(
          key,
          native.getAddressPointer.fromNative().get)
        case LibMemoryEnums.ShmRetrieveResultStatus_Tag.RetrieveDidNotExist => throw RetrieveDidNotExist(key)
        case LibMemoryEnums.ShmRetrieveResultStatus_Tag.RetrieveInternalError => throw RetrieveInternalError(
          key,
          native.error_message.get.getString(0))
      }
    }
  }

  implicit object ShmDeleteResultFromNative
      extends FromNative[ShmDeleteResult, LibMemory.ShmDeleteResult] {
    def fromNative(native: LibMemory.ShmDeleteResult): Try[ShmDeleteResult] = Try {
      val key = ShmKeyFromNative.fromNative(native).get
      native.status.get match {
        case LibMemoryEnums.ShmDeleteResultStatus_Tag.DeletionSucceeded => DeletionSucceeded(key)
        case LibMemoryEnums.ShmDeleteResultStatus_Tag.DeleteDidNotExist => throw DeleteDidNotExist(key)
        case LibMemoryEnums.ShmDeleteResultStatus_Tag.DeleteInternalError => throw DeleteInternalError(
          key,
          native.error.get.getString(0))
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
    val res = new LibMemory.ShmKey
    instance.shm_get_key(req, res)
    res.fromNative().get
  }

  def allocate(request: ShmAllocateRequest): Try[ShmAllocateResult] = Try {
    val req = request.intoNative().get
    val res = new LibMemory.ShmAllocateResult
    instance.shm_allocate(req, res)
    res.fromNative().get
  }

  def retrieve(request: ShmRetrieveRequest): Try[ShmRetrieveResult] = Try {
    val req = request.intoNative().get
    val res = new LibMemory.ShmRetrieveResult
    instance.shm_retrieve(req, res)
    res.fromNative().get
  }

  def delete(request: ShmDeleteRequest): Try[ShmDeleteResult] = Try {
    val req = request.intoNative().get
    val res = new LibMemory.ShmDeleteResult
    instance.shm_delete(req, res)
    res.fromNative().get
  }
}
