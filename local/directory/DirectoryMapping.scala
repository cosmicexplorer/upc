package upc.local.directory

import upc.local._
import upc.local.memory.{ShmKey, IntoNative, FromNative}

import ammonite.ops._

import scala.util.Try


sealed abstract class DirectoryError(message: String) extends Exception(message)


case class DirectoryNativeObjectEncodingError(message: String) extends DirectoryError(message)

sealed abstract class DirectoryExpansionError(message: String) extends DirectoryError(message)
sealed abstract class DirectoryUploadError(message: String) extends DirectoryError(message)


sealed abstract class ExpandDirectoriesResult
sealed abstract class UploadDirectoriesResult


case class ChildRelPath(path: RelPath)

case class FileStat(key: ShmKey, path: ChildRelPath)

case class PathStats(fileStats: Seq[FileStat])

case class ExpandDirectoriesRequest(digests: Seq[DirectoryDigest])

case class ExpandDirectoriesMapping(mapping: Map[DirectoryDigest, PathStats])

case class ExpandSucceeded(mapping: ExpandDirectoriesMapping) extends ExpandDirectoriesResult
case class ExpandDirectoriesFailed(message: String) extends DirectoryExpansionError(message)

case class UploadDirectoriesRequest(pathStats: Seq[PathStats])

case class UploadSucceeded(mapping: ExpandDirectoriesMapping) extends UploadDirectoriesResult
case class UploadDirectoriesFailed(message: String) extends DirectoryUploadError(message)


object DirectoryMappingIntoNative {
  import IntoNative._

  implicit object DirectoryDigestIntoNative
      extends IntoNative[DirectoryDigest, LibDirectory.DirectoryDigest] {
    def intoNative(jvm: DirectoryDigest): Try[LibDirectory.DirectoryDigest] = Try(
      LibDirectory.DirectoryDigest(ShmKey(jvm.digest).intoNative().get))
  }

  implicit object ChildRelPathIntoNative
      extends IntoNative[ChildRelPath, LibDirectory.ChildRelPath] {
    def intoNative(jvm: ChildRelPath): Try[LibDirectory.ChildRelPath] = Try(
      LibDirectory.ChildRelPath(jvm.path.toString.getBytes))
  }

  implicit object FileStatIntoNative
      extends IntoNative[FileStat, LibDirectory.FileStat] {
    def intoNative(jvm: FileStat): Try[LibDirectory.FileStat] = Try(
      LibDirectory.FileStat(
        jvm.key.intoNative().get,
        jvm.path.intoNative().get))
  }

  implicit object PathStatsIntoNative
      extends IntoNative[PathStats, LibDirectory.PathStats] {
    def intoNative(jvm: PathStats): Try[LibDirectory.PathStats] = Try(
      LibDirectory.PathStats(jvm.fileStats.map(_.intoNative().get)))
  }

  implicit object ExpandDirectoriesRequestIntoNative
      extends IntoNative[ExpandDirectoriesRequest, LibDirectory.ExpandDirectoriesRequest] {
    def intoNative(jvm: ExpandDirectoriesRequest): Try[LibDirectory.ExpandDirectoriesRequest] = Try(
      LibDirectory.ExpandDirectoriesRequest(jvm.digests.map(_.intoNative().get)))
  }

  implicit object ExpandDirectoriesMappingIntoNative
      extends IntoNative[ExpandDirectoriesMapping, LibDirectory.ExpandDirectoriesMapping] {
    def intoNative(jvm: ExpandDirectoriesMapping): Try[LibDirectory.ExpandDirectoriesMapping] = Try(
      LibDirectory.ExpandDirectoriesMapping(
        digests = jvm.mapping.keys.toSeq.map(_.intoNative().get),
        pathStats = jvm.mapping.values.toSeq.map(_.intoNative().get),
      ))
  }

  implicit object UploadDirectoriesRequestIntoNative
      extends IntoNative[UploadDirectoriesRequest, LibDirectory.UploadDirectoriesRequest] {
    def intoNative(jvm: UploadDirectoriesRequest): Try[LibDirectory.UploadDirectoriesRequest] = Try(
      LibDirectory.UploadDirectoriesRequest(jvm.pathStats.map(_.intoNative().get)))
  }
}

object DirectoryMappingFromNative {
  import FromNative._

  implicit object DirectoryDigestFromNative
      extends FromNative[DirectoryDigest, LibDirectory.DirectoryDigest] {
    def fromNative(native: LibDirectory.DirectoryDigest): Try[DirectoryDigest] = Try(
      DirectoryDigest(Digest(
        fingerprint = native.getFingerprintBytes,
        length = native.getSize,
      )))
  }

  def relpathFromBytes(bytes: Array[Byte]): RelPath = RelPath(new String(bytes, "UTF-8"))

  implicit object ChildRelPathFromNative
      extends FromNative[ChildRelPath, LibDirectory.ChildRelPath] {
    def fromNative(native: LibDirectory.ChildRelPath): Try[ChildRelPath] = Try(
      ChildRelPath(relpathFromBytes(native.getBytes)))
  }

  implicit object FileStatFromNative
      extends FromNative[FileStat, LibDirectory.FileStat] {
    def fromNative(native: LibDirectory.FileStat): Try[FileStat] = Try {
      FileStat(
        key = ShmKey(Digest(
          fingerprint = native.getFingerprintBytes,
          length = native.getSize,
        )),
        path = ChildRelPath(relpathFromBytes(native.getPathBytes)),
      )
    }
  }

  implicit object PathStatsFromNative
      extends FromNative[PathStats, LibDirectory.PathStats] {
    def fromNative(native: LibDirectory.PathStats): Try[PathStats] = Try {
      val array: Array[LibDirectory.FileStat] = new Array(native.num.get.toInt)
      PathStats(
        LibDirectory.copyStructsFrom(array, native.ptr.get) {
          new LibDirectory.FileStat
        }.map(_.fromNative().get))
    }
  }

  implicit object ExpandDirectoriesMappingFromNative
      extends FromNative[ExpandDirectoriesMapping, LibDirectory.ExpandDirectoriesMapping] {
    def fromNative(native: LibDirectory.ExpandDirectoriesMapping): Try[ExpandDirectoriesMapping] =
      Try {
        val num: Long = native.num_expansions.get

        val digestsArray: Array[LibDirectory.DirectoryDigest] = new Array(num.toInt)
        val digests: Seq[DirectoryDigest] =
          LibDirectory.copyStructsFrom(digestsArray, native.digests.get) {
            new LibDirectory.DirectoryDigest
          }.map(_.fromNative().get)

        val expansionsArray: Array[LibDirectory.PathStats] = new Array(num.toInt)
        val expansions: Seq[PathStats] =
          LibDirectory.copyStructsFrom(expansionsArray, native.expansions.get) {
            new LibDirectory.PathStats
          }.map(_.fromNative().get)

        ExpandDirectoriesMapping(digests.zip(expansions).toMap)
      }
  }

  implicit object ExpandDirectoriesResultFromNative
      extends FromNative[ExpandDirectoriesResult, LibDirectory.ExpandDirectoriesResult] {
    def fromNative(native: LibDirectory.ExpandDirectoriesResult): Try[ExpandDirectoriesResult] =
      Try {
        native.status.get match {
          case LibDirectoryEnums.ExpandDirectoriesResultStatus_Tag.ExpandDirectoriesSucceeded =>
            ExpandSucceeded(ExpandDirectoriesMappingFromNative.fromNative(native).get)
          case LibDirectoryEnums.ExpandDirectoriesResultStatus_Tag.ExpandDirectoriesFailed =>
            throw ExpandDirectoriesFailed(native.error_message.get.getString(0))
        }
      }
  }

  implicit object UploadDirectoriesResultFromNative
      extends FromNative[UploadDirectoriesResult, LibDirectory.UploadDirectoriesResult] {
    def fromNative(native: LibDirectory.UploadDirectoriesResult): Try[UploadDirectoriesResult] =
      Try {
        native.status.get match {
          case LibDirectoryEnums.UploadDirectoriesResultStatus_Tag.UploadDirectoriesSucceeded =>
            UploadSucceeded(ExpandDirectoriesMappingFromNative.fromNative(native).get)
          case LibDirectoryEnums.UploadDirectoriesResultStatus_Tag.UploadDirectoriesFailed =>
            throw UploadDirectoriesFailed(native.error_message.get.getString(0))
        }
      }
  }
}

object DirectoryMapping {
  import IntoNative._
  import DirectoryMappingIntoNative._
  import FromNative._
  import DirectoryMappingFromNative._

  import LibDirectory.instance

  def expand(request: ExpandDirectoriesRequest): Try[ExpandDirectoriesResult] = Try {
    val req = request.intoNative().get
    val res = new LibDirectory.ExpandDirectoriesResult
    instance.directories_expand(req, res)
    res.fromNative().get
  }

  def upload(request: UploadDirectoriesRequest): Try[UploadDirectoriesResult] = Try {
    val req = request.intoNative().get
    val res = new LibDirectory.UploadDirectoriesResult
    instance.directories_upload(req, res)
    res.fromNative().get
  }
}
