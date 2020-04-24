package upc.local.directory

import upc.local.memory.LibMemory.ShmKey

import jnr.ffi._


object LibDirectory {
  import LibDirectoryEnums._

  trait Iface {
    def directories_expand(request: ExpandDirectoriesRequest, result: ExpandDirectoriesResult): Unit
    def directories_upload(request: UploadDirectoriesRequest, result: UploadDirectoriesResult): Unit
  }

  abstract class FFIError(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause)

  implicit lazy val instance: Iface = {
    val lib_path = "/Users/dmcclanahan/projects/active/upc/local/target/debug"
    val loader = LibraryLoader.create(classOf[Iface])
    lib_path.split(":").foreach(loader.search(_))
    loader.load("directory")
  }
  implicit lazy val runtime = Runtime.getRuntime(instance)

  private[upc] def intoDirectPointer(bytes: Array[Byte]): Pointer = {
    val ptr = Memory.allocateDirect(runtime, bytes.length)
    ptr.put(0, bytes, 0, bytes.length)
    ptr
  }

  class DirectoryDigest(runtime: Runtime = runtime) extends ShmKey(runtime)
  object DirectoryDigest {
    def apply(key: ShmKey): DirectoryDigest = {
      val ret = new DirectoryDigest
      ret.copyKeyFrom(key)
      ret
    }
  }

  class Slice(runtime: Runtime = runtime) extends Struct(runtime) {
    val num = new Unsigned64
    val ptr = new Pointer

    def getNumElements: Long = num.get
  }

  class ChildRelPath(runtime: Runtime = runtime) extends Slice(runtime) {
    lazy val getBytes: Array[Byte] = {
      val bytes: Array[Byte] = new Array(num.get.toInt)
      ptr.get.get(0, bytes, 0, bytes.length)
      bytes
    }
  }
  object ChildRelPath {
    def apply(path: Array[Byte]): ChildRelPath = {
      val ret = new ChildRelPath
      ret.num.set(path.length)
      ret.ptr.set(intoDirectPointer(path))
      ret
    }
  }

  class FileStat(runtime: Runtime = runtime) extends ShmKey(runtime) {
    val relpath_size = new Unsigned64
    val relpath = new Pointer

    lazy val getPathBytes: Array[Byte] = {
      val bytes: Array[Byte] = new Array(relpath_size.get.toInt)
      relpath.get.get(0, bytes, 0, bytes.length)
      bytes
    }
  }
  object FileStat {
    def apply(key: ShmKey, relpath: ChildRelPath): FileStat = {
      val ret = new FileStat
      ret.copyKeyFrom(key)
      ret.relpath_size.set(relpath.getNumElements)
      ret.relpath.set(relpath.ptr.get)
      ret
    }
  }

  private[upc] def copyStructs[T <: Struct](structs: Seq[T])(
    implicit runtime: jnr.ffi.Runtime,
  ): (Long, jnr.ffi.Pointer) =
    structs match {
      case Seq() => (0, jnr.ffi.Pointer.wrap(runtime, 0))
      case Seq(first, _*) => {
        var curLength = 0
        val size = Struct.size(first)
        val intermediateArray: Array[Byte] = new Array(size)
        val ptr = Memory.allocateDirect(runtime, size * structs.length)
        structs.foreach { stat =>
          val curPtr = Struct.getMemory(stat)
          curPtr.get(0, intermediateArray, 0, size)
          ptr.put(curLength, intermediateArray, 0, size)
          curLength += size
        }
        (structs.length, ptr)
      }
    }

  // Taken from https://github.com/jnr/jnr-ffi/blob/7cd9c09f72386e46bb4803b56cf8bd62b77ded5b/src/main/java/jnr/ffi/Struct.java#L2085-L2099
  private[upc] def copyStructsFrom[T <: Struct](
    array: Array[T],
    ptr: jnr.ffi.Pointer,
  )(createStruct: => T): Seq[T] = array.length match {
    case 0 => Seq()
    case n => {
      (0 until n).foreach { i =>
        array(i) = createStruct
        array(i).useMemory(ptr.slice(Struct.size(array(i)) * i))
      }
      array.toSeq
    }
  }

  class PathStats(runtime: Runtime = runtime) extends Slice(runtime)
  object PathStats {
    def apply(file_stats: Seq[FileStat]): PathStats = {
      val ret = new PathStats
      val (num, ptr) = copyStructs(file_stats)
      ret.num.set(num)
      ret.ptr.set(ptr)
      ret
    }
  }

  class ExpandDirectoriesRequest(runtime: Runtime = runtime) extends Slice(runtime)
  object ExpandDirectoriesRequest {
    def apply(digests: Seq[DirectoryDigest]): ExpandDirectoriesRequest = {
      val ret = new ExpandDirectoriesRequest
      val (num, ptr) = copyStructs(digests)
      ret.num.set(num)
      ret.ptr.set(ptr)
      ret
    }
  }

  class ExpandDirectoriesMapping(runtime: Runtime = runtime) extends Struct(runtime) {
    val num_expansions = new Unsigned64
    val digests = new Pointer
    val expansions = new Pointer
  }
  object ExpandDirectoriesMapping {
    def apply(
      digests: Seq[DirectoryDigest],
      pathStats: Seq[PathStats],
    ): ExpandDirectoriesMapping = {
      val ret = new ExpandDirectoriesMapping
      val (digestNum, digestPtr) = copyStructs(digests)
      val (pathNum, pathPtr) = copyStructs(pathStats)
      if (digestNum != pathNum) {
        throw ExpandDirectoriesMappingError(s"expand directories mapping must have matching length digests (${digestNum}) and path stats (${pathNum})")
      }
      ret.num_expansions.set(digestNum)
      ret.digests.set(digestPtr)
      ret.expansions.set(pathPtr)
      ret
    }
  }
  case class ExpandDirectoriesMappingError(message: String) extends FFIError(message)

  class ExpandDirectoriesResult(runtime: Runtime = runtime)
      extends ExpandDirectoriesMapping(runtime) {
    val error_message = new Pointer
    val status: Enum8[ExpandDirectoriesResultStatus_Tag] =
      new Enum8(classOf[ExpandDirectoriesResultStatus_Tag])
  }

  class UploadDirectoriesRequest(runtime: Runtime = runtime) extends Slice(runtime)
  object UploadDirectoriesRequest {
    def apply(pathStats: Seq[PathStats]): UploadDirectoriesRequest = {
      val ret = new UploadDirectoriesRequest
      val (num, ptr) = copyStructs(pathStats)
      ret.num.set(num)
      ret.ptr.set(ptr)
      ret
    }
  }

  class UploadDirectoriesResult(runtime: Runtime = runtime)
      extends ExpandDirectoriesMapping(runtime) {
    val error_message = new Pointer
    val status: Enum8[UploadDirectoriesResultStatus_Tag] =
      new Enum8(classOf[UploadDirectoriesResultStatus_Tag])
  }
}
