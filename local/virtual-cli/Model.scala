package upc.local.virtual_cli

import upc.local.memory.MemoryMapping

import ammonite.ops._

import java.io.{InputStream => JavaInputStream, OutputStream => JavaOutputStream}
import scala.collection.mutable


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
  def contentCopy: Array[Byte]
  def readFrom(offset: Long, output: Array[Byte]): Int
}

trait MutableChildren[PathType, EntryType] {
  def children: mutable.Map[PathType, EntryType]
}

sealed abstract class DirEntry

case class File(bytes: MemoryMapping) extends DirEntry
    with FileContent {
  override def contentCopy: Array[Byte] = bytes.getBytesCopy

  override def readFrom(offset: Long, output: Array[Byte]): Int = bytes.readBytesAt(offset, output)
}

case class Directory(childrenMap: mutable.Map[RelPath, DirEntry]) extends DirEntry
    with MutableChildren[RelPath, DirEntry] {
  override def children: mutable.Map[RelPath, DirEntry] = childrenMap
}

class FileMapping(val allTrackedPaths: mutable.Map[Path, DirEntry]) {
  def get(path: Path): Option[DirEntry] = this.synchronized { allTrackedPaths.get(path) }
  def update(path: Path, file: File): Unit = this.synchronized {
    allTrackedPaths(path) = file
  }
}
