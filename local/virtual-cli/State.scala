package upc.local.virtual_cli

import ammonite.ops._


case class File(fd: Int)

implicit object FileDescriptor extends Descriptor[Path, File] {
  override def acquire(request: Path): File = File(request)
}


case class Directory(fd: Int)

implicit object DirectoryDescriptor extends Descriptor[Path, Directory] {
  override def acquire(request: Path): Directory = Directory(request)
}

implicit object DirectoryReader extends Readable



sealed abstract class VFSFailure {
  def descriptor: Descriptor
}
case class WriteWasDifferentLength(
  expectedLength: Int,
  actualLength: Int,
) extends java.io.IOException
    with VFSFailure



trait ByteReadable extends Readable[Int, Byte]
    with java.io.Reader {
  override def read(buf: Array[Byte], offset: Int, length: Int): Int = {
    super[Readable].read()
  }
}


trait ByteWritable extends Writable[Byte, Int]
    with java.io.Writer {
  override def write(buf: Array[Byte], offset: Int, length: Int): Unit = {
    val bytesWritten: Int = super[Writable].write(buf.slice(offset, offset + length))
    if (bytesWritten != length) {
      throw
    }
  }
  override def flush(): Unit = ()
}

case class File extends Descriptor
    with HasUniqueCanonicalPath


case class ReadOnlyFile extends File with Readable
case class WriteOnlyFile extends File with Writable
case class ReadableWritableFile extends File
    with Readable
    with Writable


case class Directory extends Descriptor
    with HasUniqueCanonicalPath


trait BytesInput extends Descriptor


trait BytesStream {
  def
}

sealed abstract class BytesInputStream extends BytesStream
sealed abstract class BytesOutputStream extends BytesStream


sealed abstract class Stdio extends BytesStream {
  val numericDescriptor: Int
}
object Stdin extends Stdio {
  val numericDescriptor = 0
}
object Stdout extends Stdio {
  val numericDescriptor = 1
}
object Stderr extends Stdio {
  val numericDescriptor = 2
}




trait State {
  def write(descriptor: Descriptor, input: Array[Byte]): Int

  def read(descriptor: Descriptor, length: Int): Array[Byte]
}
