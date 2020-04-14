package upc.local.virtual_cli

import java.io.{OutputStream => JavaOutputStream, ByteArrayOutputStream}


case class StdioResults(
  stdout: Array[Byte],
  stderr: Array[Byte],
)

trait VirtualPipeIO {
  type StreamType <: JavaOutputStream
  type ResultStreamType <: JavaOutputStream

  def locatePipe(javaStream: StreamType): Try[ResultStreamType]

  def collectResults(): StdioResults
}

trait PipeIOImplicits { self: VirtualPipeIO =>
  implicit class StreamWrapper[T <: StreamType](stream: T) {
    def locatePrintStream(): java.io.PrintStream = new java.io.PrintStream(locatePipe(stream).get)
  }
}

case class StdioStreams(
  stdout: JavaOutputStream,
  stderr: JavaOutputStream,
) {
  val inMemoryByteStreams: Map[JavaOutputStream, ByteArrayOutputStream] = Map(
    (stdout -> new ByteArrayOutputStream()),
    (stderr -> new ByteArrayOutputStream()),
  )
}

class Stdio(stdioStreams: StdioStreams) extends VirtualPipeIO {
  type StreamType = JavaOutputStream
  type ResultStreamType = JavaOutputStream

  val pipeMapping: Map[StreamType, ByteArrayOutputStream] = stdioStreams.inMemoryByteStreams

  override def locatePipe(javaStream: StreamType): Try[ResultStreamType] = Try {
    inMemoryByteStreams.get(javaStream).getOrElse {
      throw LocationFailed(s"failed to locate stream $javaStream")
    }
  }

  override def collectResults(): StdioResults = {
    val StdioStreams(origStdout, origStderr) = stdioStreams

    StdioResults(
      stdout = inMemoryByteStreams(origStdout).toByteArray,
      stderr = inMemoryByteStreams(origStderr).toByteArray,
    )
  }
}
