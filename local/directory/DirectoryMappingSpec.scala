package upc.local.directory

import upc.local.directory.testutil.TestUtil

import ammonite.ops._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class DirectoryMappingSpec extends FlatSpec with Matchers {
  "The DirectoryMapping object" should "upload and expand the same directory" in {
    val (aFile, aMapping, aKey) = TestUtil.getFile("this is file a").get
    val (bFile, bMapping, bKey) = TestUtil.getFile("this is file b").get
    val (cFile, cMapping, cKey) = TestUtil.getFile("this is file c").get

    val fileStats = Seq(
      FileStat(aKey, ChildRelPath(RelPath("a.txt"))),
      FileStat(bKey, ChildRelPath(RelPath("b.txt"))),
      FileStat(cKey, ChildRelPath(RelPath("d/e.txt"))),
    )

    val pathStats = PathStats(fileStats)
    val upload_req = UploadDirectoriesRequest(Seq(pathStats))
    val upload_result = DirectoryMapping.upload(upload_req).get
    val upload_mapping = upload_result match {
      case UploadSucceeded(mapping) => mapping
    }
    upload_mapping.mapping.size should be (1)
    val (uploadDigest, uploadStats) = upload_mapping.mapping.toSeq.apply(0)
    uploadStats should === (pathStats)

    val expand_req = ExpandDirectoriesRequest(Seq(uploadDigest))
    val expand_result = DirectoryMapping.expand(expand_req).get
    val expand_mapping = expand_result match {
      case ExpandSucceeded(mapping) => mapping
    }
    expand_mapping should === (upload_mapping)
  }

  it should "successfully merge several PathStats" in {
    val (aFile, aMapping, aKey) = TestUtil.getFile("this is file a").get
    val (bFile, bMapping, bKey) = TestUtil.getFile("this is file b").get
    val (cFile, cMapping, cKey) = TestUtil.getFile("this is file c").get

    val abStats = Seq(
      FileStat(aKey, ChildRelPath(RelPath("a.txt"))),
      FileStat(bKey, ChildRelPath(RelPath("b.txt"))),
    )
    val cStats = Seq(
      FileStat(cKey, ChildRelPath(RelPath("d/e.txt")))
    )

    DirectoryMapping.mergePathStats(Seq(
      PathStats(abStats),
      PathStats(cStats),
    )).get should === (PathStats(abStats ++ cStats))

    val Seq(abDigest, cDigest) = DirectoryMapping.upload(UploadDirectoriesRequest(Seq(
      PathStats(abStats),
      PathStats(cStats),
    ))).get match {
      case UploadSucceeded(mapping) => mapping.mapping.keys.toSeq
    }

    val expectedMergedDigest = DirectoryMapping.uploadPathStats(PathStats(abStats ++ cStats)).get

    val mergedDigest = DirectoryMapping.mergeDigests(Seq(abDigest, cDigest)).get

    mergedDigest should === (expectedMergedDigest)
  }

  it should "error out on conflicting FileStats" in {
    val (aFile, aMapping, aKey) = TestUtil.getFile("this is file a").get
    val (bFile, bMapping, bKey) = TestUtil.getFile("this is file b").get
    val (cFile, cMapping, cKey) = TestUtil.getFile("this is file c").get

    val abStats = Seq(
      FileStat(aKey, ChildRelPath(RelPath("a.txt"))),
      FileStat(bKey, ChildRelPath(RelPath("b.txt"))),
    )
    // cStats now overlaps with abStats!
    val cStats = Seq(
      FileStat(cKey, ChildRelPath(RelPath("a.txt")))
    )

    a [FailedPrecondition] should be thrownBy {
      PathStats(abStats ++ cStats)
    }

    a [FailedPrecondition] should be thrownBy {
      DirectoryMapping.mergePathStats(Seq(
        PathStats(abStats),
        PathStats(cStats),
      )).get
    }

    // This version overlaps, but has the same content!
    val fixedCStats = Seq(
      FileStat(aKey, ChildRelPath(RelPath("a.txt")))
    )
    // So this merge should work!
    DirectoryMapping.mergePathStats(Seq(
      PathStats(abStats),
      PathStats(fixedCStats),
    )).get should === (PathStats(abStats ++ fixedCStats))

    val Seq(abDigest, cDigest, fixedCDigest) = DirectoryMapping.upload(UploadDirectoriesRequest(Seq(
      PathStats(abStats),
      PathStats(cStats),
      PathStats(fixedCStats),
    ))).get match {
      case UploadSucceeded(mapping) => mapping.mapping.keys.toSeq
    }

    a [FailedPrecondition] should be thrownBy {
      DirectoryMapping.mergeDigests(Seq(abDigest, cDigest)).get
    }

    // Note: we do NOT add the ++ fixedCStats here. This is to demonstrate that the digest is the
    // same as without the fixedCDigest, which just copies a.txt.
    val expectedMergedDigest = DirectoryMapping.uploadPathStats(PathStats(abStats)).get

    val mergedDigest = DirectoryMapping.mergeDigests(Seq(abDigest, fixedCDigest)).get

    mergedDigest should === (expectedMergedDigest)
  }
}
