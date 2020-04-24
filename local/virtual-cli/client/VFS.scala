package upc.local.virtual_cli.client

import upc.local.memory.MemoryMapping

import ammonite.ops._

import java.io.{IOException, Closeable, ByteArrayOutputStream}
import scala.collection.mutable
import scala.util.Try


// Just used to get a unique symbol.
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
  type RelPathType
  type DirectoryType <: MutableChildren[RelPathType, EntryType]
}


trait AsPath[T, PathType] {
  def asPath(self: T): PathType
}


trait VFSImplicits { self: VFS =>
  object VFSImplicitDefs {
    implicit object PathAsPath extends AsPath[Path, Path] {
      override def asPath(self: Path): Path = self
    }
    implicit object FileAsPath extends AsPath[java.io.File, Path] {
      override def asPath(self: java.io.File) = Path(self)
    }

    implicit class PathWrapper[T](pathLike: T)(implicit pathConverter: AsPath[T, PathType]) {
      private def asPath: PathType = pathConverter.asPath(pathLike)
      def locateReadableStream(): Readable = openFile(asPath).map(asReadableFile(_)).get
      def locateWritableStream(): Writable = openFile(asPath).map(asWritableFile(_)).get
    }
  }
}


class DescriptorTrackingVFS(fileMapping: FileMapping) extends VFS {
  type Error = VFSError
  type PathType = Path
  type FileDescriptorType = OpenedFile
  type EntryType = DirEntry
  type FileType = File
  type RelPathType = RelPath
  type DirectoryType = Directory

  val openedPathMapping: mutable.Map[OpenedFile, Path] = mutable.Map.empty
  val fdMapping: mutable.Map[OpenedFile, File] = mutable.Map.empty
  val fdSeekPositions: mutable.Map[OpenedFile, Long] = mutable.Map.empty
  val pendingWrites: mutable.Map[OpenedFile, WritableFile] = mutable.Map.empty

  override def openFile(path: Path): Try[OpenedFile] = Try {
    fileMapping.get(path) match {
      case None => throw LocationFailed(s"failed to locate path $path")
      case Some(x: File) => {
        val descriptor = OpenedFile(new FD)
        openedPathMapping(descriptor) = path
        fdMapping(descriptor) = x
        fdSeekPositions(descriptor) = 0
        descriptor
      }
      case Some(x) => throw WrongEntryType(s"result $x was not a file")
    }
  }
  override def updateFileContents(path: Path, file: File): Unit = {
    fileMapping.update(path, file)
  }

  def newHandle(openedFile: OpenedFile): FileHandle = new FileHandle(openedFile, this)

  override def asReadableFile(openedFile: OpenedFile): Readable =
    new ReadableFile(newHandle(openedFile))
  override def asWritableFile(openedFile: OpenedFile): Writable =
    new WritableFile(newHandle(openedFile))

  override def scanDir(path: Path): Try[Directory] = Try {
    fileMapping.get(path) match {
      case None => throw LocationFailed(s"failed to locate path $path")
      case Some(x: Directory) => x
      case Some(x) => throw WrongEntryType(s"result $x was not a directory")
    }
  }
  override def mkdir(path: Path): Try[Directory] = Try {
    ???
  }

  override def currentFileMapping: FileMapping = fileMapping

  def readAll(openedFile: OpenedFile): Array[Byte] = this.synchronized {
    getFile(openedFile).contentCopy
  }
  def readBytesAt(openedFile: OpenedFile, output: Array[Byte]): Int = this.synchronized {
    getFile(openedFile).readFrom(getSeek(openedFile), output)
  }

  def getFile(openedFile: OpenedFile): File = fdMapping(openedFile)
  def getPath(openedFile: OpenedFile): Path = openedPathMapping(openedFile)
  def getSeek(openedFile: OpenedFile): Long = fdSeekPositions(openedFile)

  def closeFile(openedFile: OpenedFile): Unit = this.synchronized {
    openedPathMapping -= openedFile
    fdMapping -= openedFile
    fdSeekPositions -= openedFile
  }

  def closeWriteFile(openedFile: OpenedFile, newFile: File): Unit = this.synchronized {
    val prevPath = getPath(openedFile)
    closeFile(openedFile)
    pendingWrites -= openedFile
    updateFileContents(prevPath, newFile)
  }
}

class FileHandle(openedFile: OpenedFile, vfs: DescriptorTrackingVFS) {
  def getFile(): File = vfs.getFile(openedFile)
  def getPath(): Path = vfs.getPath(openedFile)
  def getSeek(): Long = vfs.getSeek(openedFile)

  def readBytesAt(output: Array[Byte]): Int = vfs.readBytesAt(openedFile, output)
  def readAll(): Array[Byte] = vfs.readAll(openedFile)

  def closeForRead(): Unit = vfs.closeFile(openedFile)
  def closeForWrite(newFile: File): Unit = vfs.closeWriteFile(openedFile, newFile)
}

class ReadableFile(handle: FileHandle) extends Readable with Closeable {
  override protected def readBytes(output: Array[Byte]): Int = handle.readBytesAt(output)

  override def readAll(): Array[Byte] = handle.readAll()

  override def close(): Unit = handle.closeForRead()
}

class WritableFile(handle: FileHandle) extends Writable with Closeable {
  val sink = new ByteArrayOutputStream()

  override protected def writeBytes(input: Array[Byte]): Unit = sink.write(input, 0, input.length)

  override def writeAll(input: Array[Byte]): Unit = writeBytes(input)

  override def close(): Unit = handle.closeForWrite(File(MemoryMapping.fromArray(sink.toByteArray)))
}
