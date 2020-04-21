package upc.local.memory

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import java.util.UUID

@RunWith(classOf[JUnitRunner])
class MemoryMappingSpec extends FlatSpec with Matchers {
  "The Shm object" should "successfully retrieve the correct ShmKey for a string" in {
    val knownSource = "asdf".getBytes
    val mapping = MemoryMapping.fromArray(knownSource)
    mapping.getBytes should be (knownSource)

    val req = ShmGetKeyRequest(mapping)
    val key = Shm.getKey(req).get
    key.length should be (knownSource.length)
    key.fingerprintHex should be ("f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b")
  }

  it should "successfully allocate and retrieve shared memory" in {
    val randomSource = UUID.randomUUID().toString.getBytes
    val mapping = MemoryMapping.fromArray(randomSource)
    mapping.getBytes should be (randomSource)

    val key_req = ShmGetKeyRequest(mapping)
    val key = Shm.getKey(key_req).get
    key.length should be (randomSource.length)

    val retrieve_req = ShmRetrieveRequest(key)
    a [RetrieveDidNotExist$] should be thrownBy {
      Shm.retrieve(retrieve_req).get
    }
    val delete_req = ShmDeleteRequest(key)
    a [DeleteDidNotExist$] should be thrownBy {
      Shm.delete(delete_req).get
    }

    val allocate_req = ShmAllocateRequest(key, mapping)
    val allocate_result = Shm.allocate(allocate_req).get
    val shared_mapping = allocate_result match {
      case AllocationSucceeded(src) => src
    }
    // shared_mapping.getBytes should be (randomSource)

    // val retro

    // retrieve_result should be a [Success]
  }
}
