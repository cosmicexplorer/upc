package upc.local.thrift_socket

import ammonite.ops._
import org.apache.thrift.transport.{TSocket, TServerSocket}
import org.newsclub.net.unix.{AFUNIXSocket, AFUNIXServerSocket, AFUNIXSocketAddress}

import scala.util.Try


class ThriftUnixClient(socket: AFUNIXSocket) extends TSocket(socket)

object ThriftUnixClient {
  def apply(path: Path): Try[ThriftUnixClient] = Try {
    val sock = AFUNIXSocket.newInstance
    val addr = new AFUNIXSocketAddress(path.toIO)
    sock.connect(addr)
    new ThriftUnixClient(sock)
  }
}

class ThriftUnixServer(socket: AFUNIXServerSocket) extends TServerSocket(socket)

object ThriftUnixServer {
  def apply(path: Path): Try[ThriftUnixServer] = Try {
    val sock = AFUNIXServerSocket.newInstance
    val addr = new AFUNIXSocketAddress(path.toIO)
    sock.bind(addr)
    new ThriftUnixServer(sock)
  }
}
