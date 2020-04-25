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

case class InputStdio(
  stdout: JavaPrintStream,
  stderr: JavaPrintStream,
)
object InputStdio {
  def acquire(): InputStdio = InputStdio(
    stdout = System.out,
    stderr = System.err)
}

class InMemoryStdio(input: InputStdio) {
  lazy val stdout = InMemoryOutputStreamWrapper.create()
  lazy val stderr = InMemoryOutputStreamWrapper.create()

  def collect(): StdioResults = {
    System.setOut(input.stdout)
    System.setErr(input.stderr)
    StdioResults(
      stdout = stdout.getBytes,
      stderr = stderr.getBytes,
    )
  }
}
object InMemoryStdio {
  def acquire(): InMemoryStdio = {
    val ret = new InMemoryStdio(InputStdio.acquire())
    System.setOut(ret.stdout)
    System.setErr(ret.stderr)
    ret
  }
}
