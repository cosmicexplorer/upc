package upc.local.virtual_cli

import ammonite.ops._
import org.apache.thrift.transport.TSocket
import org.newsclub.net.unix.{AFUNIXSocket, AFUNIXSocketAddress}

import java.io.{Closeable => JavaCloseable}
import scala.concurrent.{blocking, ExecutionContext, Future}


class ThriftUnixSocket(path: Path) extends JavaCloseable {
  lazy val unixSocket: AFUNIXSocket = {
    val sock = AFUNIXSocket.newInstance
    val address = new AFUNIXSocketAddress(path.toIO)
    sock.connect(address)
    sock
  }
  lazy val thriftSocket = new TSocket(unixSocket)

  override def close(): Unit = thriftSocket.close()
}