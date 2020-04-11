namespace rs upc.local.glob_matching

include "fs.thrift"
include "locators.thrift"


struct SinglePathGlob {
  1: optional string glob,
}
struct PathGlobs {
  1: optional list<SinglePathGlob> include_globs,
  2: optional list<SinglePathGlob> exclude_globs,
}

struct ExpandGlobsRequest {
  1: optional PathGlobs path_globs,
}
struct ExpandGlobsResult {
  1: optional locators.DirectoryDigest directory_digest,
}

enum GlobMatchingErrorCode {
  INVALID_ARGUMENT = 1,
  INTERNAL_ERROR = 2,
}
exception GlobMatchingError {
  1: optional GlobMatchingErrorCode error_code,
  2: optional string description,
}

service GlobMatchingService {
  ExpandGlobsResult expandGlobs(1: ExpandGlobsRequest expand_globs_request)
    throws (1: GlobMatchingError glob_matching_error)
}
