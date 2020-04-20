package upc.local.memory

import jnr.ffi._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import java.nio.ByteBuffer
import java.util.UUID
import javax.xml.bind.DatatypeConverter
import scala.util.{Try, Success}

@RunWith(classOf[JUnitRunner])
class MemoryMappingSpec extends FlatSpec with Matchers {
  import LibMemory._
  import LibMemoryEnums._

  "The Shm object" should "successfully retrieve the correct ShmKey for a string" in {
    val knownSource = "asdf".getBytes
    val ptr = Memory.allocate(runtime, knownSource.length)
    ptr.put(0, knownSource, 0, knownSource.length)
    val req = ShmGetKeyRequest(ptr)
    val key = instance.shm_get_key(req)
    key.getSize should be (knownSource.length)
    val fp = DatatypeConverter.printHexBinary(key.getFingerprintBytes)
    fp should be ("F0E4C2F76C58916EC258F246851BEA091D14D4247A2FC3E18694461B1816E13B")
  }

  it should "successfully allocate and retrieve shared memory" in {
    val randomSource = UUID.randomUUID().toString.getBytes
    val ptr = Memory.allocate(runtime, randomSource.length)
    ptr.put(0, randomSource, 0, randomSource.length)

    val key_req = ShmGetKeyRequest(ptr)
    val key = instance.shm_get_key(key_req)

    val allocate_req = ShmAllocateRequest(key, ptr)
    val allocate_result = instance.shm_allocate(allocate_req)
    allocate_result.tag.get should be (ShmAllocateResult_Tag.AllocationSucceeded)
    throw new RuntimeException(s"wow: ${}")

    // val req = ShmRetrieveRequest()
    // val mapping = MemoryMapping.fromArray(randomSource.getBytes)
    // val request = ShmAllocateRequest(mapping)
    // val result = Shm.allocate(request)
    // result should be a [Success]
  }
}
