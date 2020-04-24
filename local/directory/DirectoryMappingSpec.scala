package upc.local.directory

import upc.local.memory._

import ammonite.ops._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.util.Try


@RunWith(classOf[JUnitRunner])
class DirectoryMappingSpec extends FlatSpec with Matchers {
  def getFile(contents: String): Try[(Array[Byte], MemoryMapping, ShmKey)] = Try {
    val bytes = contents.getBytes
    val inputMapping = MemoryMapping.fromArray(bytes)
    val key = Shm.getKey(ShmGetKeyRequest(inputMapping)).get

    val allocateRequest = ShmAllocateRequest(key, inputMapping)
    val (allocatedKey, sharedMapping) = Shm.allocate(allocateRequest).get match {
      case AllocationSucceeded(key, src) => (key, src)
    }
    allocatedKey should === (key)
    sharedMapping.getBytes should === (bytes)

    (bytes, sharedMapping, key)
  }

  "The DirectoryMapping object" should "upload and expand the same directory" in {
    val (aFile, aMapping, aKey) = getFile("this is file a").get

    val (bFile, bMapping, bKey) = getFile("this is file b").get

    val (cFile, cMapping, cKey) = getFile("this is file c").get

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
}
