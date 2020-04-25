package upc.local.virtual_cli.client

import upc.local._
import upc.local.memory._

import ammonite.ops._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try


object ExampleMain extends VirtualizationImplementation {
  val stdoutOutput = "asdf"

  override def acquireIOServicesConfig(): Try[IOServicesConfig] = Try(IOServicesConfig(
    initialDigest = DirectoryDigest(Digest.empty),
    executor = global,
  ))

  override def virtualizedMainMethod(args: Array[String]): Int = {
    import VirtualIOLayer.Implicits._

    System.out.println(stdoutOutput)
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
    digest should === (DirectoryDigest(Digest.empty))
    stderr should === (ShmKey(Digest.empty))

    val knownOutput = (ExampleMain.stdoutOutput + "\n").getBytes
    val mapping = MemoryMapping.fromArray(knownOutput)
    val key = Shm.getKey(ShmGetKeyRequest(mapping)).get
    stdout should === (key)
  }
}
