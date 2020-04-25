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


object ExampleMain extends VirtualizationImplementation {
  val stdoutOutput = "asdf"
  val fileOutput = "this is a.txt!"

  override def acquireIOServicesConfig(): Try[IOServicesConfig] = Try(IOServicesConfig(
    initialDigest = DirectoryDigest(Digest.empty),
    executor = global,
  ))

  override def virtualizedMainMethod(args: Array[String]): Int = {
    import VirtualIOLayer.Implicits._

    System.out.println(stdoutOutput)

    "a.txt".locateWritableStream().writeAll(fileOutput.getBytes)
    0
  }
}


@RunWith(classOf[JUnitRunner])
class VirtualizationImplementationSpec extends FlatSpec with Matchers {
  "The ExampleMain object" should "successfully virtualize its execution" in {
    val CompleteVirtualizedProcessResult(
      ExitCode(exitCode),
      IOFinalState(digest, stdout, stderr)
    ) = Await.result(ExampleMain.mainWrapper(Array(), pwd), Duration.Inf)
    exitCode should be (0)
    stderr should === (ShmKey(Digest.empty))

    val knownOutput = (ExampleMain.stdoutOutput + "\n").getBytes
    val stdoutMapping = MemoryMapping.fromArray(knownOutput)
    val key = Shm.getKey(ShmGetKeyRequest(stdoutMapping)).get
    stdout should === (key)

    val expandReq = ExpandDirectoriesRequest(Seq(digest))
    val dirMapping = DirectoryMapping.expand(expandReq).get match {
      case ExpandSucceeded(ExpandDirectoriesMapping(mapping)) => mapping
    }
    dirMapping.size should be (1)
    val (retDigest, pathStats) = dirMapping.toSeq.apply(0)
    retDigest should === (digest)

    val fileMapping = MemoryMapping.fromArray(ExampleMain.fileOutput.getBytes)
    val fileKey = Shm.getKey(ShmGetKeyRequest(fileMapping)).get
    val expectedPathStats = PathStats(Seq(
      FileStat(
        key = fileKey,
        path = ChildRelPath(RelPath("a.txt")),
      )))

    pathStats should === (expectedPathStats)
  }
}
