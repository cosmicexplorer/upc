namespace rs upc.local.fs

include "locators.thrift"


struct DirectoryStat {
  1: optional string directory_path,
  2: optional locators.DirectoryDigest directory_digest,
}
struct FileStat {
  1: optional string file_path,
  2: optional locators.FileDigest file_digest,
}

struct ExpandDirectoryRequest {
  1: optional locators.DirectoryDigest directory_digest,
}
struct ExpandDirectoryResult {
  1: optional list<FileStat> file_stats,
  2: optional list<DirectoryStat> directory_stats,
}

enum DirectoryExpansionErrorCode {
  DIGEST_NOT_FOUND = 1,
  INTERNAL_ERROR = 2,
}
exception DirectoryExpansionError {
  1: optional DirectoryExpansionErrorCode error_code,
  2: optional string description,
}

service DirectoryExpansionService {
  ExpandDirectoryResult expandDirectory(1: ExpandDirectoryRequest expand_directory_request)
    throws (1: DirectoryExpansionError directory_expansion_error)
}
