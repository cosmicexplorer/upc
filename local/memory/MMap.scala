package upc.local.memory

import upc.local.{FileDigest, MemoryMappedRegionId, LocalSlice}

import jnr.ffi.Pointer

import java.nio.ByteBuffer
import scala.util.{Try, Success, Failure}


case class ShmKey(key: String)

case class ShmRequest(key: ShmKey, sizeBytes: Int)

sealed trait ShmResultTag
case object ShmSucceeded extends ShmResultTag
case object ShmFailed extends ShmResultTag

sealed abstract class ShmError(message: String) extends Exception(message)
case class ShmAllocationError(message: String) extends ShmError

sealed trait ShmResult {
  def intoTry: Try[Pointer] = Try { this match {
    case ShmSuccess(pointer) => pointer
    case ShmFailure(e) => throw e
  }}
}
case class ShmSuccess(pointer: Pointer) extends ShmResult
case class ShmFailure(e: ShmError) extends ShmResult


trait IntoNative[JvmType, NativeType] {
  def intoNative(jvm: JvmType): NativeType
}
object IntoNative {
  implicit class JvmToNativeWrapper[JvmType, NativeType](jvm: JvmType)(
    implicit intoNative: IntoNative[JvmType, NativeType]
  ) {
    def intoNative: NativeType = intoNative.intoNative(jvm)
  }

  implicit object ShmKeyIntoNative extends IntoNative[ShmKey, LibMemory.ShmKey] {
    def intoNative(jvm: ShmKey): LibMemory.ShmKey = {
      val key = new LibMemory.ShmKey
      key.string.set(jvm.key)
      key.length.set(jvm.key.length)
      key
    }
  }
  implicit object ShmRequestIntoNative extends IntoNative[ShmRequest, LibMemory.ShmRequest] {
    def intoNative(jvm: ShmRequest): LibMemory.ShmRequest = {
      val request = new LibMemory.ShmRequest
      request.key.set(jvm.key.intoNative)
      request.size_bytes.set(jvm.sizeBytes)
      // TODO: Will we always need to be using the write permission for all clients?
      request.permission.set(LibMemory.Write)
      request
    }
  }
}

trait FromNative[JvmType, NativeType] {
  def fromNative(native: NativeType): JvmType
}
object FromNative {
  implicit class JvmFromNativeWrapper[JvmType, NativeType](native: NativeType)(
    implicit fromNative: FromNative[JvmType, NativeType]
  ) {
    def fromNative: JvmType = fromNative.fromNative(native)
  }

  implicit object ShmResultTagFromNative extends FromNative[ShmResultTag, LibMemory.ShmResult_Tag] {
    def fromNative(native: LibMemory.ShmResult_Tag): ShmResultTag = native match {
      case LibMemory.ShmResult_Tag.Succeeded => ShmSucceeded
      case LibMemory.ShmResult_Tag.Failed => ShmFailed
    }
  }
  implicit object ShmResultFromNative extends FromNative[ShmResult, LibMemory.ShmResult] {
    def fromNative(native: LibMemory.ShmResult): ShmResult = native.tag.get.fromNative match  {
      case ShmSucceeded => ShmSuccess(native.body.get.succeeded.get._0)
      case ShmFailed => ShmFailure(ShmAllocationError(native.body.get.failed.get._0.getString(0)))
    }
  }
}

case class MMap(pointer: Pointer)

object MMap {


  def parseResult(shmResult: ShmResult): Try[MMap] = ShmResult
}
