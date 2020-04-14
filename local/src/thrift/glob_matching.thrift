namespace rs upc.local.thrift_rust.glob_matching
namespace java upc.local.thrift_java.glob_matching

include "directory.thrift"


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
// TODO: make this ExpandGlobsAsyncResult and return a token to allow for greater parallelism when
// invoking processes that depend on the result of a glob expansion!!
struct ExpandGlobsResult {
  1: optional directory.DirectoryDigest directory_digest,
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
