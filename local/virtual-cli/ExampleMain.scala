package upc.local.virtual_cli

import upc.local.virtual_cli.client.VirtualIOLayer.Implicits._

import ammonite.ops._


object ExampleMain extends MainWrapper {
  override def virtualizedMainMethod(args: Array[String]): Int = {
    System.out.println("asdf")
    0
  }
}
