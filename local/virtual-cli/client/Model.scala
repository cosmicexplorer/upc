package upc.local.virtual_cli.client

import upc.local.directory._
import upc.local.memory._

import ammonite.ops._

import java.io.{InputStream => JavaInputStream, OutputStream => JavaOutputStream}
import scala.collection.mutable
import scala.util.Try


trait Readable extends JavaInputStream {
  // We're not making these methods return a Try, because we expect them to be extremely hot.
  protected def readBytes(output: Array[Byte]): Int

  def readAll(): Array[Byte]

  override def read(buf: Array[Byte], offset: Int, length: Int): Int =
    readBytes(buf.slice(offset, offset + length))

  override def read(buf: Array[Byte]): Int = readBytes(buf)

  // Returning an int to mean "a byte read from the stream" here while meaning "the number of bytes
  // returned by the stream" as for the other overrides feels very error-prone, especially when it's
  // technically the only abstract method for the class.
  override def read(): Int = {
    val input = Array(0.toByte)
    // NB: We *ignore* the result here, because we return the *byte* as the return value!!
    readBytes(input)
    input(0).toInt
  }
}

trait Writable extends JavaOutputStream {
  protected def writeBytes(input: Array[Byte]): Unit

  def writeAll(input: Array[Byte]): Unit

  override def write(buf: Array[Byte], offset: Int, length: Int): Unit =
    writeBytes(buf.slice(offset, offset + length))

  override def write(buf: Array[Byte]): Unit = writeBytes(buf)

  // See above on JavaInputStream and how reading an "int" here to mean a byte is confusing.
  override def write(byte: Int): Unit = writeBytes(Array(byte.toByte))

  override def flush(): Unit = ()
}


trait FileContent {
  def content: Array[Byte]
  def readFrom(offset: Long, output: Array[Byte]): Int
}

trait MutableChildren[PathType, EntryType] {
  def children: mutable.Map[PathType, EntryType]
}

case class File(bytes: MemoryMapping) extends FileContent {
  override lazy val content: Array[Byte] = bytes.getBytes

  override def readFrom(offset: Long, output: Array[Byte]): Int = bytes.readBytesAt(offset, output)
}

case class FileMapping(allTrackedPaths: mutable.Map[Path, File]) {
  def get(path: Path): Option[File] = this.synchronized { allTrackedPaths.get(path) }
  def update(path: Path, file: File): Unit = this.synchronized {
    allTrackedPaths(path) = file
  }

  def intoPathStats(cwd: Path): Try[PathStats] = Try {
    val paths: Seq[(Path, File)] = allTrackedPaths.toSeq
    val fileStats = paths.map {
      case (path, file) => {
        val File(mapping) = file
        // TODO: Only retain the files below cwd, and only retain the files matching the process
        // execution request PathGlobs, if provided!!
        val relPath = path.relativeTo(cwd)
        val key = Shm.getKey(ShmGetKeyRequest(mapping)).get
        val () = Shm.allocate(ShmAllocateRequest(key, mapping)).get match {
          case AllocationSucceeded(_key, _mapping) => ()
        }
        FileStat(key, ChildRelPath(relPath))
      }
    }
    PathStats(fileStats)
  }
}
object FileMapping {
  def fromPathStats(cwd: Path, pathStats: PathStats): Try[FileMapping] = Try {
    val PathStats(fileStats) = pathStats
    val paths: Seq[(Path, File)] = fileStats.map {
      case FileStat(key, ChildRelPath(path)) => {
        val file = Shm.retrieve(ShmRetrieveRequest(key)).get match {
          case RetrieveSucceeded(_key, mapping) => File(mapping)
        }
        ((cwd / path) -> file)
      }
    }
    val map: mutable.Map[Path, File] = mutable.Map.empty
    paths.foreach {
      case (path, file) => {
        map(path) = file
      }
    }
    FileMapping(map)
  }
}
