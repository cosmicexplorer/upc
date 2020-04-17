package upc.local.memory

import jnr.ffi.Pointer

import java.nio.ByteBuffer
import scala.util.{Try, Success, Failure}


sealed abstract class ShmError(message: String) extends Exception(message)
case class ShmNativeObjectEncodingError(message: String) extends ShmError(message)
case class ShmAllocationError(message: String) extends ShmError(message)
case class ShmRetrieveError(message: String) extends ShmError(message)
case class ShmDeleteError(message: String) extends ShmError(message)


case class Fingerprint(fingerprint: String) {
  if (fingerprint.length != 32) {
    throw ShmNativeObjectEncodingError(s"invalid fingerprint: must be 32 bytes (was: $fingerprint)")
  }
}
case class ShmKey(fingerprint: Fingerprint, length: Int) {
  if (length < 0) {
    throw ShmNativeObjectEncodingError(s"invalid ShmKey: length must be non-negative (was: $this)")
  }
}

case class MemoryMapping(pointer: Pointer)
object MemoryMapping {
  def fromByteBuffer(buf: ByteBuffer) = MemoryMapping(Pointer.wrap(LibMemory.runtime, buf))
}

case class ShmAllocateRequest(key: ShmKey, source: MemoryMapping)
sealed abstract class ShmAllocateResult
case class AllocationSucceeded(source: MemoryMapping) extends ShmAllocateResult
case class DigestDidNotMatch(key: ShmKey) extends ShmAllocationError(this.toString)
case class AllocationFailed(error: String) extends ShmAllocateResult(this.toString)

case class ShmRetrieveRequest(key: ShmKey)
sealed abstract class ShmRetrieveResult
case class RetrieveSucceeded(source: MemoryMapping) extends ShmRetrieveResult
case object RetrieveDidNotExist extends ShmRetrieveError(this.toString)
case class RetrieveInternalError(error: String) extends ShmRetrieveError(this.toString)

case class ShmDeleteRequest(key: ShmKey)
sealed abstract class ShmDeleteResult
case object DeletionSucceeded extends ShmDeleteResult
case object DeleteDidNotExist extends ShmDeleteError(this.toString)
case class DeleteInternalError(error: String) extends ShmDeleteError(this.toString)


trait IntoNative[JvmType, NativeType] {
  def intoNative(jvm: JvmType): NativeType
}
object IntoNative {
  implicit class JvmToNativeWrapper[JvmType, NativeType](jvm: JvmType)(
    implicit intoNative: IntoNative[JvmType, NativeType]
  ) {
    def intoNative: NativeType = intoNative.intoNative(jvm)
  }

  implicit object FingerprintIntoNative extends IntoNative[Fingerprint, LibMemory.Fingerprint] {
    def intoNative(jvm: Fingerprint): LibMemory.Fingerprint = {
      val fp = new LibMemory.Fingerprint
      fp._0.set(jvm.fingerprint.getBytes)
      fp
    }
  }
  implicit object ShmKeyIntoNative extends IntoNative[ShmKey, LibMemory.ShmKey] {
    def intoNative(jvm: ShmKey): LibMemory.ShmKey = {
      val key = new LibMemory.ShmKey
      key.fingerprint.set(jvm.fingerprint.intoNative)
      key.length.set(jvm.length)
      key
    }
  }

  implicit object MemoryMappingIntoNative extends IntoNative[MemoryMapping, Pointer] {
    def intoNative(jvm: MemoryMapping): Pointer = jvm.pointer
  }

  implicit object MemoryMappingIntoNative[MemoryMapping, Pointer] {
    def intoNative(jvm: MemoryMapping): Pointer = Pointer.wrap(LibMemory.runtime, jvm)
  }

  implicit object ShmAllocateRequestIntoNative
      extends IntoNative[ShmAllocateRequest, LibMemory.ShmAllocateRequest] {
    def intoNative(jvm: ShmAllocateRequest): LibMemory.ShmAllocateRequest = {
      val request = new LibMemory.ShmAllocateRequest
      request.key.set(jvm.key.intoNative)
      request.source.set(jvm.source.intoNative)
      request
    }
  }

  implicit object ShmRetrieveRequestIntoNative
      extends IntoNative[ShmRetrieveRequest, LibMemory.ShmRetrieveRequest] {
    def intoNnative(jvm: ShmRetrieveRequest): LibMemory.ShmRetrieveRequest = {
      val request = LibMemory.ShmRetrieveRequest
      request.key.set(jvm.key.intoNative)
      request
    }
  }

  implicit object ShmDeleteRequestIntoNative
      extends IntoNative[ShmDeleteRequest, LibMemory.ShmDeleteRequest] {
    def intoNnative(jvm: ShmDeleteRequest): LibMemory.ShmDeleteRequest = {
      val request = LibMemory.ShmDeleteRequest
      request.key.set(jvm.key.intoNative)
      request
    }
  }
}

trait FromNative[JvmType, NativeType] {
  // This one is a Try[_] because some native return values represent errors, which we convert to
  // exceptions!
  def fromNative(native: NativeType): Try[JvmType]
}
object FromNative {
  implicit class JvmFromNativeWrapper[JvmType, NativeType](native: NativeType)(
    implicit fromNative: FromNative[JvmType, NativeType]
  ) {
    def fromNative(): Try[JvmType] = fromNative.fromNative(native)
  }

  implicit object FingerprintFromNative
      extends FromNative[Fingerprint, LibMemory.Fingerprint] {
    def fromNative(native: LibMemory.Fingerprint): Try[Fingerprint] = Try(Fingerprint(
      Arrays.toString(native._0.get)))
  }
  implicit object ShmKeyFromNative
      extends FromNative[ShmKey, LibMemory.ShmKey] {
    def fromNative(native: LibMemory.ShmKey): Try[ShmKey] = Try(ShmKey(
      fingerprint = native.fingerprint.get.fromNative().get,
      length = native.length.get.toInt,
    ))
  }

  implicit object MemoryMappingFromNative
      extends FromNative[MemoryMapping, Pointer] {
    def fromNative(native: Pointer): Try[MemoryMapping] = Try(MemoryMapping(native))
  }

  implicit object ShmAllocateResultFromNative
      extends FromNative[ShmAllocateResult, LibMemory.ShmAllocateResult] {
    def fromNative(native: LibMemory.ShmAllocateResult): Try[ShmAllocateResult] = Try {
      native.tag.get match {
        case LibMemory.ShmAllocateResult_Tag.AllocationSucceeded => AllocationSucceeded(
          native.body.get.allocation_succeeded.get._0.fromNative().get)
        case LibMemory.ShmAllocateResult_Tag.DigestDidNotMatch => throw DigestDidNotMatch(
          native.body.get.digest_did_not_match.get._0.fromNative().get)
        case LibMemory.ShmAllocateResult_Tag.AllocationFailed => throw AllocationFailed(
          native.body.get.allocation_failed.get._0.getString(0))
      }
    }
  }

  implicit object ShmRetrieveResultFromNative
      extends FromNative[ShmRetrieveResult, LibMemory.ShmRetrieveResult] {
    def fromNative(native: LibMemory.ShmRetrieveResult): Try[ShmRetrieveResult] = Try {
      native.tag.get match {
        case LibMemory.ShmRetrieveResult_Tag.RetrieveSucceeded => RetrieveSucceeded(
          native.body.get.retrieve_succeeded.get._0.fromNative().get)
        case LibMemory.ShmRetrieveResult_Tag.RetrieveDidNotExist => throw RetrieveDidNotExist
        case LibMemory.ShmRetrieveResult_Tag.RetrieveInternalError => throw RetrieveInternalError(
          native.body.get.retrieve_internal_error.get._0.getString(0))
      }
    }
  }

  implicit object ShmDeleteResultFromNative
      extends FromNative[ShmDeleteResult, LibMemory.ShmDeleteResult] {
    def fromNative(native: LibMemory.ShmDeleteResult): Try[ShmDeleteResult] = Try {
      native.tag.get match {
        case LibMemory.ShmDeleteResult_Tag.DeletionSucceeded => DeletionSucceeded
        case LibMemory.ShmDeleteResult_Tag.DeleteDidNotExist => throw DeleteDidNotExist
        case LibMemory.ShmDeleteResult_Tag.DeleteInternalError => throw DeleteInternalError(
          native.body.get.delete_internal_error.get._0.getString(0))
      }
    }
  }
}

case class MMap(pointer: Pointer)

object MMap {


  def parseResult(shmResult: ShmResult): Try[MMap] = ShmResult
}
