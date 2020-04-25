package upc.local.virtual_cli.client

import upc.local._
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

case class ReadFD[T](fd: T)
case class WriteFD[T](fd: T)


trait VFS {
  def openFileRead(path: Path): Try[ReadFD[FileDescriptorType]]
  def openFileWrite(path: Path): Try[WriteFD[FileDescriptorType]]
  def updateFileContents(path: Path, file: FileType): Unit
  def asReadableFile(descriptor: ReadFD[FileDescriptorType]): Readable
  def asWritableFile(descriptor: WriteFD[FileDescriptorType]): Writable
  def expandGlobs(globs: GlobsType): Try[Seq[Path]]

  def cwd: Path

  def currentFileMapping: FileMappingType

  type Error <: VFSError
  type FileDescriptorType
  type FileType <: FileContent
  type GlobsType
  type FileMappingType
}


trait AsPath[T] {
  def asPath(self: T): Path
}


trait VFSImplicits { self: VFS =>
  object VFSImplicitDefs {
    implicit object PathAsPath extends AsPath[Path] {
      override def asPath(self: Path): Path = self
    }
    implicit object FileAsPath extends AsPath[java.io.File] {
      override def asPath(self: java.io.File) = Path(self)
    }
    implicit object StringAsPath extends AsPath[String] {
      override def asPath(self: String) = cwd / self
    }

    implicit class PathWrapper[T: AsPath](pathLike: T) {
      private val pathConverter = implicitly[AsPath[T]]
      private def asPath: Path = pathConverter.asPath(pathLike)
      def locateReadableStream(): Readable = openFileRead(asPath).map(asReadableFile(_)).get
      def locateWritableStream(): Writable = openFileWrite(asPath).map(asWritableFile(_)).get
    }
  }
}


class DescriptorTrackingVFS(_cwd: Path, fileMapping: FileMapping) extends VFS {
  type Error = VFSError
  type FileDescriptorType = OpenedFile
  type FileType = File
  type GlobsType = PathGlobs
  type FileMappingType = FileMapping

  override def cwd: Path = _cwd

  override def currentFileMapping: FileMapping = fileMapping

  val openedPathMapping: mutable.Map[OpenedFile, Path] = mutable.Map.empty
  val fdMapping: mutable.Map[ReadFD[OpenedFile], File] = mutable.Map.empty
  val fdSeekPositions: mutable.Map[ReadFD[OpenedFile], Long] = mutable.Map.empty
  val pendingWrites: mutable.Map[WriteFD[OpenedFile], WritableFile] = mutable.Map.empty

  override def openFileRead(path: Path): Try[ReadFD[OpenedFile]] = Try {
    currentFileMapping.get(path) match {
      case None => throw LocationFailed(s"failed to locate path $path")
      case Some(x) => {
        val descriptor = OpenedFile(new FD)
        openedPathMapping(descriptor) = path
        ReadFD(descriptor)
      }
    }
  }
  override def openFileWrite(path: Path): Try[WriteFD[OpenedFile]] = Try {
    val descriptor = OpenedFile(new FD)
    openedPathMapping(descriptor) = path
    WriteFD(descriptor)
  }


  override def updateFileContents(path: Path, file: File): Unit = {
    fileMapping.update(path, file)
  }

  override def asReadableFile(openedFile: ReadFD[OpenedFile]): Readable = {
    val readable = new ReadableFile(openedFile, this)
    val path = getPath(openedFile.fd)
    val file = currentFileMapping.get(path).get
    fdMapping(openedFile) = file
    fdSeekPositions(openedFile) = 0
    readable
  }
  override def asWritableFile(openedFile: WriteFD[OpenedFile]): Writable = {
    val writable = new WritableFile(openedFile, this)
    pendingWrites(openedFile) = writable
    writable
  }

  override def expandGlobs(globs: PathGlobs): Try[Seq[Path]] = Try { ??? }

  def readAll(openedFile: ReadFD[OpenedFile]): Array[Byte] = this.synchronized {
    fdMapping(openedFile).content
  }
  def readBytesAt(openedFile: ReadFD[OpenedFile], output: Array[Byte]): Int = this.synchronized {
    fdMapping(openedFile).readFrom(getSeek(openedFile), output)
  }

  def getPath(openedFile: OpenedFile): Path = openedPathMapping(openedFile)
  def getSeek(openedFile: ReadFD[OpenedFile]): Long = fdSeekPositions(openedFile)

  def closeReadFile(openedFile: ReadFD[OpenedFile]): Unit = this.synchronized {
    openedPathMapping -= openedFile.fd
    fdMapping -= openedFile
    fdSeekPositions -= openedFile
  }

  def flushWriteFile(openedFile: WriteFD[OpenedFile], newFile: File): Unit = this.synchronized {
    val prevPath = getPath(openedFile.fd)
    updateFileContents(prevPath, newFile)
  }

  def closeWriteFile(openedFile: WriteFD[OpenedFile], newFile: File): Unit = this.synchronized {
    val prevPath = getPath(openedFile.fd)
    openedPathMapping -= openedFile.fd
    pendingWrites -= openedFile
    updateFileContents(prevPath, newFile)
  }
}

class ReadableFile(
  fd: ReadFD[OpenedFile],
  vfs: DescriptorTrackingVFS,
) extends Readable with Closeable {
  override protected def readBytes(output: Array[Byte]): Int = vfs.readBytesAt(fd, output)

  override def readAll(): Array[Byte] = {
    val ret = vfs.readAll(fd)
    close()
    ret
  }

  override def close(): Unit = vfs.closeReadFile(fd)
}

class WritableFile(
  fd: WriteFD[OpenedFile],
  vfs: DescriptorTrackingVFS,
) extends Writable with Closeable {
  val sink = new ByteArrayOutputStream()

  override protected def writeBytes(input: Array[Byte]): Unit = sink.write(input, 0, input.length)

  override def writeAll(input: Array[Byte]): Unit = {
    writeBytes(input)
    close()
  }

  private def asNewFile = File(MemoryMapping.fromArray(sink.toByteArray))

  override def flush(): Unit = vfs.flushWriteFile(fd, asNewFile)

  override def close(): Unit = vfs.closeWriteFile(fd, asNewFile)
}
