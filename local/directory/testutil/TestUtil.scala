package upc.local.directory.testutil

import upc.local.memory._

import scala.util.Try


object TestUtil {
  def getFile(contents: String): Try[(Array[Byte], MemoryMapping, ShmKey)] = Try {
    val bytes = contents.getBytes
    val inputMapping = MemoryMapping.fromArray(bytes)
    val key = Shm.getKey(ShmGetKeyRequest(inputMapping)).get

    val allocateRequest = ShmAllocateRequest(key, inputMapping)
    val (allocatedKey, sharedMapping) = Shm.allocate(allocateRequest).get match {
      case AllocationSucceeded(key, src) => (key, src)
    }

    (bytes, sharedMapping, key)
  }
}
