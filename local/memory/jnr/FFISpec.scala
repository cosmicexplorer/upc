package upc.local.memory.jnr

import jnr.ffi._
import jnr.ffi.types._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import java.nio.ByteBuffer
import java.util.UUID
import javax.xml.bind.DatatypeConverter
import scala.util.{Try, Success}

@RunWith(classOf[JUnitRunner])
class FFISpec extends FlatSpec with Matchers {
  import LibMemory._

  "The LibMemory object" should "successfully add one" in {
    val source = "asdf".getBytes
    val ptr = Memory.allocate(runtime, 4)
    ptr.put(0, source, 0, source.length)
    val req = ShmGetKeyRequest(ptr)
    val key = instance.shm_get_key(req)
    key.size_bytes.longValue should be (4L)
    val fp = DatatypeConverter.printHexBinary(key.getBytes)
    fp should be ("F0E4C2F76C58916EC258F246851BEA091D14D4247A2FC3E18694461B1816E13B")
  }

  // "The LibMemory object" should "successfully retrieve the correct ShmKey for a string" in {
  //   val knownSource = "asdf".getBytes
  //   val ptr = Memory.allocate(runtime, knownSource.length)
  //   ptr.put(0, knownSource, 0, knownSource.length)
  //   val request = ShmGetKeyRequest(ptr)
  //   val shmKey = instance.shm_get_key(request)
  //   shmKey.getLength should be (4)
  //   // instance.shm_get_key(request) should be (ShmKey(
  //   //   Fingerprint("d1bc8d3ba4afc7e109612cb73acbdddac052c93025aa1f82942edabb7deb82a1".getBytes),
  //   //   4))
  // }
}
