package upc.local.virtual_cli.client

import upc.local._
import upc.local.memory._

import VirtualIOLayer.Implicits._

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

  override def virtualizedMainMethod(args: Array[String], cwd: Path): Int = {
    System.out.println(stdoutOutput)
    0
  }
}


@RunWith(classOf[JUnitRunner])
class VirtualizationImplementationSpec extends FlatSpec with Matchers {
  "The ExampleMain object" should "successfully virtualize its execution" in {
    val result = Await.result(ExampleMain.mainWrapper(Array(), Path(".")), Duration.Inf)
    val knownOutput = (ExampleMain.stdoutOutput + "\n").getBytes
    val mapping = MemoryMapping.fromArray(knownOutput)
    val key = Shm.getKey(ShmGetKeyRequest(mapping)).get
    result.stdout should === (key)
    result.stderr should === (ShmKey(Digest.empty))
  }
}
