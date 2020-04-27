package upc.local.virtual_cli

import upc.local.virtual_cli.client.MainWrapper
import upc.local.virtual_cli.client.VirtualIOLayer.Implicits._


object ExampleMain extends MainWrapper {
  override def virtualizedMainMethod(args: Array[String]): Int = {
    val (inputStream, outputStream) = args match {
      case Array(inputFile, outputFile) => {
        val outputStream = if (outputFile == "-") System.out else outputFile.locateWritableStream()
        (inputFile.locateReadableStream() -> outputStream)
      }
      case x => throw new RuntimeException(s"unrecognized args: $x. expected [input-file] [output-file]")
    }

    inputStream.pipe(outputStream)

    0
  }
}
