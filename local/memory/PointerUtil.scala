package upc.local.memory

import jnr.ffi._


object PointerUtil {
  implicit class PointerWrapper(ptr: Pointer) {
    def copyToDirectMemory(implicit runtime: jnr.ffi.Runtime): Pointer = {
      val n = ptr.size.toInt match {
        case -1 => 0
        case x => x
      }
      val newPtr = Memory.allocateDirect(runtime, n)
      // TODO: can probably remove this unnecessary double-copy!!
      val intermediateArray: Array[Byte] = new Array(n)
      ptr.get(0, intermediateArray, 0, n)
      newPtr.put(0, intermediateArray, 0, n)
      newPtr
    }
  }
}
