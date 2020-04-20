package upc.local.memory

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import java.util.UUID
import scala.util.{Try, Success}

@RunWith(classOf[JUnitRunner])
class MemoryMappingSpec extends FlatSpec with Matchers {
  "The Shm object" should "successfully retrieve the correct ShmKey for a string" in {
    val knownSource = "asdf"
    val mapping = MemoryMapping.fromArray(knownSource.getBytes)
    val request = ShmGetKeyRequest(mapping)
    Shm.getKey(request).get should be (ShmKey(
      Fingerprint("d1bc8d3ba4afc7e109612cb73acbdddac052c93025aa1f82942edabb7deb82a1".getBytes),
      4))
  }

  // it should "successfully allocate and retrieve shared memory" in {
  //   val randomSource = UUID.randomUUID().toString
  //   val mapping = MemoryMapping.fromArray(randomSource.getBytes)
  //   val request = ShmAllocateRequest(mapping)
  //   val result = Shm.allocate(request)
  //   result should be a [Success]
  // }
}
