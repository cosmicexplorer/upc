package upc.local.virtual_cli

import VirtualIOLayer.Implicits._

import ammonite.ops._


object ExampleMain extends MainWrapper {
  override def virtualizedMainMethod(args: Array[String], cwd: Path): Int = {
    System.out.println("asdf")
    0
  }
}
