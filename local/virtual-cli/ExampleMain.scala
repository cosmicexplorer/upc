package upc.local.virtual_cli

import VFS.Implicits._

import ammonite.ops._


object ExampleMain extends MainWrapper {
  val THRIFT_SOCKET_LOCATION_ENV_VAR = "VIRTUAL_CLI_THRIFT_SOCKET_PATH"

  def mainImpl(args: Array[String], cwd: Path, exit: Int => Unit): Unit = {
    val stdout = VFS.locatePipe(System.stdout).get
    stdout.writeBytes("asdf".getBytes)
  }
}
