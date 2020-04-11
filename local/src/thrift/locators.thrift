namespace rs upc.local.locators

// Identifies a file or directory node on a local or remote machine.
struct Digest {
  1: optional string fingerprint,
  2: optional i64 size_bytes,
}

// A digest identifying a file. This may then be mapped to a memory.LocalSlice with a subsequent
// request.
struct FileDigest {
  1: optional Digest digest,
}

// A digest identifying a directory. This is only bubbled up here for now because it exists in the
// bazel remote execution API -- it may move to "fs.thrift".
struct DirectoryDigest {
  1: optional Digest digest,
}
p
