package upc.local.virtual_cli.server

import upc.local._
import upc.local.directory._
import upc.local.directory.testutil.TestUtil
import upc.local.thrift.ViaThrift._
import upc.local.thriftjava.{process_execution => thriftjava}
import upc.local.thrift_socket._

import ammonite.ops._
import org.apache.thrift.protocol.TBinaryProtocol
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import java.io.Closeable
import java.nio.file.Files
import scala.concurrent.Future
import scala.reflect.io.Directory
import scala.util.Try


case class SocketDirectory(dir: Path) extends Closeable {
  val exePath = dir / "exe.socket"
  val reapPath = dir / "reap.socket"

  lazy val exeSocket = ThriftSocketServer.fromPath(exePath).get
  lazy val reapSocket = ThriftSocketServer.fromPath(reapPath).get

  lazy val serverConfig = ProcessExecutionServerFoundation(
    processExecutionService = exeSocket,
    processReapService = reapSocket
  )
  lazy val server = new ProcessExecutionServer(serverConfig)

  lazy val exeClientSocket = ThriftUnixClient(exePath).get
  lazy val exeClient = new thriftjava.ProcessExecutionService.Client(
    new TBinaryProtocol(exeClientSocket))

  lazy val reapClientSocket = ThriftUnixClient(reapPath).get
  lazy val reapClient = new thriftjava.ProcessReapService.Client(
    new TBinaryProtocol(reapClientSocket))

  def serve(): Seq[Future[Unit]] = server.run()

  def stop(): Unit = {
    server.stop()
  }

  override def close(): Unit = {
    stop()
    new Directory(dir.toIO).deleteRecursively()
  }
}
object SocketDirectory {
  def create(): Try[SocketDirectory] = Try {
    val dir = Files.createTempDirectory("exe").toFile
    SocketDirectory(Path(dir))
  }
}



@RunWith(classOf[JUnitRunner])
class ProcessExecutionServerSpec extends FlatSpec with Matchers {
  val socketDir = SocketDirectory.create().get
  import socketDir.{exeClient, reapClient}

  override def withFixture(test: NoArgTest) = {
    socketDir.serve()
    try super.withFixture(test)
    finally {
      socketDir.close()
    }
  }

  val testBinJar = "/Users/dmcclanahan/projects/active/upc/dist/bin.jar"

  val (aFile, _, aKey) = TestUtil.getFile("this is file a").get
  val aStats = PathStats(Seq(FileStat(aKey, ChildRelPath(RelPath("a.txt")))))
  val aDigest = DirectoryMapping.uploadPathStats(aStats).get

  // NB: reapProcess is not tested because the return value is void! It will be implicitly tested by
  // running the process executions that are explicitly tested in this file!
  "The ProcessExecutionServer object" should "execute a single process correctly" in {
    val req = VirtualizedExecuteProcessRequest(
      conjoinedRequests = Seq(
        BasicExecuteProcessRequest(
          baseRequest = ReducedExecuteProcessRequest(
            argv = Seq(
              "/Library/Java/JavaVirtualMachines/TwitterJDK11/Contents/Home/bin/java",
              "-jar", testBinJar,
              "a.txt", "-"),
            env = Map(("JAVA_HOME" -> "/Library/Java/JavaVirtualMachines/TwitterJDK11/Contents/Home")),
          )
        ))
    )
    val res = exeClient.executeProcesses(req.toThrift).fromThrift().get
    res.exitCode.code should be (0)
    res.ioState.stdout should === (aKey)
  }

  // test("#executes multiple concurrent processes and cats the stdout") {
  //   // write files "a.txt" and "b.txt" into the initial digests, make subprocesses cat them, and
  //   // then cat the stdout of those subprocesses.
  //   await(client.executeProcesses(???))
  // }

  // test("#executes multiple concurrent processes and merges the vfs digests") {
  //   // write files "a.txt" and "b.txt" into the initial digests, make subprocesses write to two NEW
  //   // files, and then read the merged digest of the output.
  //   await(client.executeProcesses(???))
  // }
}
