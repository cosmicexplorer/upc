namespace rs upc.local.thrift_rust.file
namespace java upc.local.thrift_java.file

struct MappingId {
  // The application should use a library which allows it to map this id to an (anonymous?) shared
  // memory region on the local machine!
  1: optional string id,
}

struct MemoryMappedRegionId {
  1: optional MappingId mapping_id,
  // These happen to be the POSIX arguments required when specifying a named or anonymous mapping.
  2: optional i64 offset,
  3: optional i64 size,
}
struct LocalSlice {
  1: optional MemoryMappedRegionId id,
  // These fields may constitute a sub-slice of a larger memory-mapped region identified by the
  // MemoryMappedRegionId. The consumer of this struct should not need to be aware of that.
  2: optional i64 offset,
  3: optional i64 size,
}

// A digest identifying a file. This may then be mapped to a file.LocalSlice with a subsequent
// request.
struct FileDigest {
  1: optional string fingerprint,
  2: optional i64 size_bytes,
}

struct BasicExpandFileRequest {
  1: optional FileDigest file_digest,
  2: optional i64 offset,
  3: optional i64 size,
}
struct ExpandFilesRequest {
  1: optional list<BasicExpandFileRequest> requests,
}
struct ExpandFilesResult {
  1: optional list<LocalSlice> local_slices,
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

// This expects that the memory was already mapped (with MAP_SHARED) at a location which can be
// extracted from another program via `id` (by definition).
struct BasicUploadFileRequest {
  1: optional MemoryMappedRegionId id,
}
struct UploadFilesRequest {
  1: optional list<BasicUploadFileRequest> requests,
}
struct UploadFilesResult {
  1: optional map<MemoryMappedRegionId, FileDigest> digested_files,
}

service MemoryMappingService {
  ExpandFilesResult expandFiles(1: ExpandFilesRequest expand_files_request)
    throws (1: MemoryMappingError memory_mapping_error)

  UploadFilesResult uploadFiles(1: UploadFilesRequest upload_files_request)
    throws (1: MemoryMappingError memory_mapping_error)
}
