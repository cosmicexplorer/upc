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
