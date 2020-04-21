package upc.local.directory

import jnr.ffi._


object LibDirectory {
  import LibDirectoryEnums._

  trait Iface {
    def directories_expand()
  }
}
