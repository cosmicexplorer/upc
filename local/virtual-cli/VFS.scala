package upc.local.virtual_cli

import ammonite.ops._

import java.io.{IOException, Closeable, ByteArrayOutputStream}
import scala.collection.mutable
import scala.util.Try


class FD

case class OpenedFile(fd: FD)


sealed abstract class VFSError(message: String) extends IOException(message)
case class LocationFailed(message: String) extends VFSError(message)
case class WrongEntryType(message: String) extends VFSError(message)


trait VFS {
  def openFile(path: PathType): Try[FileDescriptorType]
  def updateFileContents(path: PathType, file: FileType): Unit
  def asReadableFile(descriptor: FileDescriptorType): Readable
  def asWritableFile(descriptor: FileDescriptorType): Writable
  def scanDir(path: PathType): Try[DirectoryType]
  def mkdir(path: PathType): Try[DirectoryType]

  def currentFileMapping: FileMapping

  type Error <: VFSError
  type PathType
  type FileDescriptorType
  type EntryType
  type FileType <: FileContent
  type DirectoryType <: MutableChildren[PathType, EntryType]
}


trait AsPath[T] {
  def asPath(self: T): Path
}


trait VFSImplicits { self: VFS =>
  object Implicits {
    implicit object PathAsPath extends AsPath[Path] {
      override def asPath(self: Path): Path = self
    }
    implicit object FileAsPath extends AsPath[java.io.File] {
      override def asPath(self: java.io.File) = Path(self)
    }

    implicit class PathWrapper[T: AsPath](pathLike: T) {
      private def pathConverter = implicitly[AsPath[T]]
      private def asPath: Path = pathConverter.asPath(pathLike)
      def locateReadableStream(): Readable = openFile(asPath).map(_.asReadableFile).get
      def locateWritableStream(): Writable = openFile(asPath).map(_.asWritableFile).get
    }
  }
}


class DescriptorTrackingVFS(fileMapping: FileMapping) extends VFS {
  type Error = VFSError
  type PathType = Path
  type FileDescriptorType = OpenedFile
  type EntryType = DirEntry
  type FileType = File
  type DirectoryType = Directory

  val openedPathMapping: mutable.Map[OpenedFile, Path] = mutable.Map.empty
  val fdMapping: mutable.Map[OpenedFile, File] = mutable.Map.empty
  val fdSeekPositions: mutable.Map[OpenedFile, SeekPosition] = mutable.Map.empty
  val pendingWrites: mutable.Map[OpenedFile, WritableFile] = mutable.Map.empty

  override def openFile(path: Path): Try[OpenedFile] = Try {
    fileMapping.get(path) match {
      case None => throw LocationFailed(s"failed to locate path $path"),
      case Some(x: File) => {
        val descriptor = OpenedFile(new FD)
        openedPathMapping(descriptor) = path
        fdMapping(descriptor) = x
        fdSeekPositions(descriptor) = SeekPosition(0)
        descriptor
      },
      case Some(x) => throw WrongEntryType(s"result $x was not a file"),
    }
  }
  override def updateFileContents(path: Path, file: File): Unit = {
    fileMapping.update(path, file)
  }
  override def asReadableFile(opened_file: OpenedFile): Readable = new ReadableFile(opened_file)
  override def asWritableFile(opened_file: OpenedFile): Writable = new WritableFile(opened_file)
  override def scanDir(path: Path): Try[Directory] = Try {
    fileMapping.get(path) match {
      case None => throw LocationFailed(s"failed to locate path $path"),
      case Some(x: Directory) => x,
      case Some(x) => throw WrongEntryType(s"result $x was not a directory"),
    }
  }
  override def mkdir(path: Path): Try[Directory] = Try {
    ???
  }

  override def currentFileMapping: FileMapping = fileMapping

  abstract class FileHandle(opened_file: OpenedFile) extends Closeable {
    protected def getFile(): File = fdMapping(opened_file)
    protected def getPath(): Path = openedPathMapping(opened_file)
    protected def getSeek(): SeekPosition = fdSeekPositions(opened_file)

    protected def nonSyncClose(): Unit = {
      openedPathMapping -= opened_file
      fdMapping -= opened_file
      fdSeekPositions -= opened_file
    }

    override def close(): Unit = DescriptorTrackingVFS.synchronized {
      nonSyncClose()
    }
  }

  class ReadableFile(opened_file: OpenedFile) extends FileHandle(opened_file) with Readable {
    override protected def readBytes(output: Array[Byte]): Int = {
      val readableBytes = DescriptorTrackingVFS.synchronized {
        getFile().seekTo(getSeek())
      }
      readableBytes.copyToArray(output)
    }

    override def readAll(): Array[Byte] = DescriptorTrackingVFS.synchronized { getFile().content }
  }

  class WritableFile(opened_file: OpenedFile) extends FileHandle(opened_file) with Writable {
    val sink = new ByteArrayOutputStream()

    override protected def writeBytes(input: Array[Byte]): Unit = {
      sink.write(input, 0, input.length)
    }

    override def writeAll(input: Array[Byte]): Unit = writeBytes(input)

    override protected def nonSyncClose(): Unit = {
      super[FileHandle].nonSyncClose()
      pendingWrites -= opened_file
      val newFile = File(sink.toByteArray)
      updateFileContents(getPath(), newFile)
    }
  }
}
