namespace rs upc.local.thrift_rust.directory
namespace java upc.local.thrift_java.directory

include "file.thrift"


// A digest identifying a directory.
struct DirectoryDigest {
  // NB: The empty fingerprint is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.
  // This can be found e.g. by `sha256sum /dev/null`.
  // NB: This string must be 64 bytes long! A different value is considered invalid.
  1: optional string fingerprint,
  // The empty digest has a size of 0. A negative value for this is considered invalid.
  2: optional i64 size_bytes,
}


struct DirectoryStat {
  1: optional string directory_path,
  2: optional DirectoryDigest directory_digest,
}
struct FileStat {
  1: optional string file_path,
  2: optional file.FileDigest file_digest,
}
struct PathStats {
  1: optional list<FileStat> file_stats,
  2: optional list<DirectoryStat> directory_stats,
}


// NB: Right now, we expect that we would add some more complex unpacking operations like
// "select specific subdirs" or "unzip this file" as *separate* services, like with
// glob_matching.GlobMatchingService!
enum ExpansionRecursionType {
  RECURSE_SUBDIRS = 1,
  INTRANSITIVE = 2,
}
struct BasicExpandDirectoryRequest {
  1: optional DirectoryDigest directory_digest,
  2: optional ExpansionRecursionType expansion_recursion_type,
}
struct ExpandDirectoryRequest {
  1: optional list<BasicExpandDirectoryRequest> requests,
}
struct ExpandDirectoryResult {
  1: optional map<BasicExpandDirectoryRequest, PathStats> resolved_path_stats,
}

enum DirectoryExpansionErrorCode {
  DIGEST_NOT_FOUND = 1,
  INTERNAL_ERROR = 2,
}
exception DirectoryExpansionError {
  1: optional DirectoryExpansionErrorCode error_code,
  2: optional string description,
}


// This represents the recursive contents of the directory.
struct BasicUploadDirectoryRequest {
  1: optional PathStats path_stats,
}
struct UploadDirectoriesRequest {
  1: optional list<BasicUploadDirectoryRequest> requests,
}
struct UploadDirectoriesResult {
  1: optional map<BasicUploadDirectoryRequest, DirectoryDigest> uploaded_directory_digests,
}

enum UploadDirectoriesErrorCode {
  INTERNAL_ERROR = 1,
}
exception UploadDirectoriesError {
  1: optional UploadDirectoriesErrorCode error_code,
  2: optional string description,
}

service DirectoryService {
  ExpandDirectoryResult expandDirectory(1: ExpandDirectoryRequest expand_directory_request)
    throws (1: DirectoryExpansionError directory_expansion_error)

  UploadDirectoriesResult uploadDirectories(1: UploadDirectoriesRequest upload_directories_request)
    throws (1: UploadDirectoriesError upload_directories_error)
}
