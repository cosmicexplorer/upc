package upc.local.virtual_cli.client

import java.io.{ByteArrayOutputStream, PrintStream => JavaPrintStream}


case class StdioResults(
  stdout: Array[Byte],
  stderr: Array[Byte],
)

class InMemoryOutputStreamWrapper(sink: ByteArrayOutputStream) extends JavaPrintStream(sink) {
  def getBytes = sink.toByteArray
}
object InMemoryOutputStreamWrapper {
  def create() = new InMemoryOutputStreamWrapper(new ByteArrayOutputStream())
}

object InMemoryStdio {
  lazy val stdout = InMemoryOutputStreamWrapper.create()
  lazy val stderr = InMemoryOutputStreamWrapper.create()

  def acquire(): Unit = {
    System.setOut(stdout)
    System.setErr(stderr)
  }

  def collect() = StdioResults(
    stdout = stdout.getBytes,
    stderr = stderr.getBytes,
  )
}
