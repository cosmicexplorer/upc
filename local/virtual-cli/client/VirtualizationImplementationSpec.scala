package upc.local.virtual_cli.client

import upc.local._
import upc.local.directory._
import upc.local.memory._

import ammonite.ops._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try


@RunWith(classOf[JUnitRunner])
class VirtualizationImplementationSpec extends FlatSpec with Matchers {

  object ExampleMain extends VirtualizationImplementation {
    val stdoutOutput = "asdf"
    val fileOutput = "this is a.txt!"

    override def acquireIOServicesConfig(): Try[IOServicesConfig] = Try(IOServicesConfig(
      initialDigest = DirectoryDigest(Digest.empty),
      executor = global,
    ))

    override def virtualizedMainMethod(args: Array[String]): Int = {
      import ioLayer.Implicits._

      System.out.println(stdoutOutput)

      "a.txt".locateWritableStream().writeAll(fileOutput.getBytes)
      0
    }
  }

  "The ExampleMain object" should "successfully virtualize its execution" in {
    val CompleteVirtualizedProcessResult(
      ExitCode(exitCode),
      IOFinalState(digest, stdout, stderr)
    ) = Await.result(ExampleMain.mainWrapper(Array(), pwd), Duration.Inf)
    exitCode should be (0)
    stderr should === (ShmKey(Digest.empty))

    val knownOutput = (ExampleMain.stdoutOutput + "\n").getBytes
    val stdoutKey = Shm.keyFor(knownOutput).get
    stdout should === (stdoutKey)

    val pathStats = DirectoryMapping.expandDigest(digest).get
    val expectedPathStats = PathStats(Seq(
      FileStat(
        key = Shm.keyFor(ExampleMain.fileOutput.getBytes).get,
        path = ChildRelPath(RelPath("a.txt")),
      )))

    pathStats should === (expectedPathStats)
  }

  class ReadWriteMain extends VirtualizationImplementation {
    val inputStr = "input text!"
    val input = inputStr.getBytes
    val (inputKey, _) = Shm.allocateBytes(input).get
    val pathStats = PathStats(Seq(
      FileStat(
        key = inputKey,
        path = ChildRelPath(RelPath("file1.txt"))
      )
    ))
    val dirDigest = DirectoryMapping.uploadPathStats(pathStats).get

    override def acquireIOServicesConfig(): Try[IOServicesConfig] = Try(IOServicesConfig(
      initialDigest = dirDigest,
      executor = global,
    ))

    val appendExtraStr = "extra text!!"

    override def virtualizedMainMethod(args: Array[String]): Int = {
      import ioLayer.Implicits._

      val Array(inFile, outFile, _*) = args
      val input = inFile.locateReadableStream().readAll()
      val toWrite = if (args.length > 2) {
        (inputStr + appendExtraStr).getBytes
      } else input
      outFile.locateWritableStream().writeAll(toWrite)
      0
    }
  }

  // Different instances are needed here in order to have different instances of VirtualIOLayer, to
  // avoid mixing output with files from other tests.
  object ReadWriteMain1 extends ReadWriteMain
  object ReadWriteMain2 extends ReadWriteMain

  "The ReadWriteMain object" should "successfully read and write separate files" in {
    val CompleteVirtualizedProcessResult(
      ExitCode(exitCode),
      IOFinalState(digest, stdout, stderr)
    ) = Await.result(ReadWriteMain1.mainWrapper(Array("file1.txt", "file2.txt"), pwd), Duration.Inf)
    exitCode should be (0)
    stdout should === (ShmKey(Digest.empty))
    stderr should === (ShmKey(Digest.empty))

    val pathStats = DirectoryMapping.expandDigest(digest).get
    val expectedPathStats = PathStats(Seq(
      FileStat(
        key = Shm.keyFor(ReadWriteMain1.input).get,
        path = ChildRelPath(RelPath("file1.txt"))
      ),
      FileStat(
        key = Shm.keyFor(ReadWriteMain1.input).get,
        path = ChildRelPath(RelPath("file2.txt")),
      ),
    ))

    pathStats.fileStats.toSet should === (expectedPathStats.fileStats.toSet)
  }

  it should "successfully modify the contents of an existing file" in {
    val CompleteVirtualizedProcessResult(
      ExitCode(exitCode),
      IOFinalState(digest, stdout, stderr)
    ) = Await.result(ReadWriteMain2.mainWrapper(
      Array("file1.txt", "file1.txt", "THIS-ARGUMENT-WRITES-MORE-TO-FILE1"), pwd),
      Duration.Inf)
    exitCode should be (0)
    stdout should === (ShmKey(Digest.empty))
    stderr should === (ShmKey(Digest.empty))

    val pathStats = DirectoryMapping.expandDigest(digest).get
    val expectedOutput = (ReadWriteMain2.inputStr + ReadWriteMain2.appendExtraStr).getBytes
    val expectedPathStats = PathStats(Seq(
      FileStat(
        key = Shm.keyFor(expectedOutput).get,
        path = ChildRelPath(RelPath("file1.txt"))
      ),
    ))

    pathStats should === (expectedPathStats)
  }
}
