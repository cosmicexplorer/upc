namespace rs upc.local.thrift_rust.file
namespace java upc.local.thrift_java.file

struct MemoryMappedRegionId {
  // The application should use a library which allows it to map this id to an (anonymous?) shared
  // memory region on the local machine!
  1: optional string id,
}

struct LocalSlice {
  1: optional MemoryMappedRegionId id,
  2: optional i64 size_bytes,
}

// A digest identifying a file. This may then be mapped to a file.LocalSlice with a subsequent
// request.
struct FileDigest {
  1: optional string fingerprint,
  2: optional i64 size_bytes,
}
