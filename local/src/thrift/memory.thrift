namespace rs upc.local.memory

include "locators.thrift"


// The application should use a library which allows it to map this id to an (anonymous?) shared
// memory region on the local machine!
struct MemoryMappedRegionId {
  1: optional locators.FileDigest file_digest,
  // These happen to be the POSIX arguments required when specifying a named or anonymous mapping.
  2: optional i64 offset,
  3: optional i64 size,
}
struct LocalSlice {
  1: optional MemoryMappedRegionId id,
  // These fields may constitute a sub-slice of a larger memory-mapped region identified by the
  // MemoryMappedRegionId. The consumer of this struct should not need to be aware of this.
  2: optional i64 offset,
  3: optional i64 size,
}


struct ExpandFileRequest {
  1: optional locators.FileDigest file_digest,
}
struct ExpandFileResult {
  1: optional LocalSlice local_slice,
}

enum MemoryMappingErrorCode {
  FILE_NOT_FOUND = 1,
  MAPPING_FAILED = 2,
  INTERNAL_ERROR = 3,
}
exception MemoryMappingError {
  1: optional MemoryMappingErrorCode error_code,
  2: optional string description,
}

service MemoryMappingService {
  ExpandFileResult expandFile(1: ExpandFileRequest expand_file_request)
    throws (1: MemoryMappingError memory_mapping_error)
}
