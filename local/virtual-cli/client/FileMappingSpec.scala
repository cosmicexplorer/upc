package upc.local.virtual_cli.client

import upc.local.directory._
import upc.local.directory.testutil.TestUtil

import ammonite.ops._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class ModelSpec extends FlatSpec with Matchers {
  "A FileMapping instance" should "be bijective with PathStats" in {
    val (aFile, aMapping, aKey) = TestUtil.getFile("this is file a").get

    val (bFile, bMapping, bKey) = TestUtil.getFile("this is file b").get

    val (cFile, cMapping, cKey) = TestUtil.getFile("this is file c").get

    val fileStats = Seq(
      FileStat(aKey, ChildRelPath(RelPath("a.txt"))),
      FileStat(bKey, ChildRelPath(RelPath("b.txt"))),
      FileStat(cKey, ChildRelPath(RelPath("d/e.txt"))),
    )
    val pathStats = PathStats(fileStats)

    val fileMapping = FileMapping.fromPathStats(pwd, pathStats).get

    val retPathStats = fileMapping.intoPathStats(pwd).get

    pathStats.fileStats.toSet should === (retPathStats.fileStats.toSet)
  }
}
