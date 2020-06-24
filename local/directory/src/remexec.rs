use super::*;

use memory::shm::*;
pub use memory::shm::{block_on_with_persistent_runtime, LOCAL_STORE, PANTS_WORKUNIT_STORE};

use bazel_protos::remote_execution as remexec_api;
use boxfuture::{BoxFuture, Boxable};
use bytes::Bytes;
use fs::FileContent;
use hashing::{Digest, Fingerprint};

use futures01::{future, Future};
use protobuf;

use std::convert::{From, Into};
use std::str;

#[derive(Debug)]
pub enum RemexecError {
  InternalError(String),
}
impl From<String> for RemexecError {
  fn from(err: String) -> Self {
    RemexecError::InternalError(err)
  }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum FilePermissions {
  Executable,
  None,
}

/* Necessary to mediate conversions between the remexec_api digests and pants
 * hashing::Digests. This could be done upstream, maybe. */
pub struct RemexecDigestWrapper {
  pub fingerprint: Fingerprint,
  pub size_bytes: usize,
}
impl From<remexec_api::Digest> for RemexecDigestWrapper {
  fn from(digest: remexec_api::Digest) -> Self {
    RemexecDigestWrapper {
      fingerprint: Fingerprint::from_hex_string(&digest.hash).unwrap(),
      size_bytes: digest.size_bytes as usize,
    }
  }
}
impl Into<remexec_api::Digest> for RemexecDigestWrapper {
  fn into(self: Self) -> remexec_api::Digest {
    let mut ret = remexec_api::Digest::new();
    ret.set_hash(self.fingerprint.to_hex());
    ret.set_size_bytes(self.size_bytes as i64);
    ret
  }
}
impl From<Digest> for RemexecDigestWrapper {
  fn from(digest: Digest) -> Self {
    let Digest(fingerprint, size_bytes) = digest;
    RemexecDigestWrapper {
      fingerprint,
      size_bytes,
    }
  }
}
impl Into<Digest> for RemexecDigestWrapper {
  fn into(self: Self) -> Digest {
    Digest(self.fingerprint, self.size_bytes)
  }
}

#[derive(Debug)]
pub struct FileNode {
  pub name: String,
  pub key: ShmKey,
  pub permissions: FilePermissions,
}
impl From<remexec_api::FileNode> for FileNode {
  fn from(node: remexec_api::FileNode) -> Self {
    let digest_wrapper: RemexecDigestWrapper = node.get_digest().clone().into();
    let digest: Digest = digest_wrapper.into();
    let key: ShmKey = digest.into();
    FileNode {
      name: node.get_name().to_string(),
      key,
      permissions: match node.get_is_executable() {
        true => FilePermissions::Executable,
        false => FilePermissions::None,
      },
    }
  }
}
impl Into<remexec_api::FileNode> for FileNode {
  fn into(self: Self) -> remexec_api::FileNode {
    let FileNode {
      name,
      key,
      permissions,
    } = self;
    let mut ret = remexec_api::FileNode::new();
    ret.set_name(name);
    let digest: Digest = key.into();
    let digest_wrapper: RemexecDigestWrapper = digest.into();
    ret.set_digest(digest_wrapper.into());
    ret.set_is_executable(match permissions {
      FilePermissions::Executable => true,
      FilePermissions::None => false,
    });
    ret
  }
}

#[derive(Debug)]
pub struct DirectoryNode {
  pub name: String,
  pub digest: DirectoryDigest,
}
impl From<remexec_api::DirectoryNode> for DirectoryNode {
  fn from(node: remexec_api::DirectoryNode) -> Self {
    let digest_wrapper: RemexecDigestWrapper = node.get_digest().clone().into();
    let digest: Digest = digest_wrapper.into();
    DirectoryNode {
      name: node.get_name().to_string(),
      digest: digest.into(),
    }
  }
}
impl Into<remexec_api::DirectoryNode> for DirectoryNode {
  fn into(self: Self) -> remexec_api::DirectoryNode {
    let DirectoryNode { name, digest } = self;
    let digest: Digest = digest.into();
    let digest_wrapper: RemexecDigestWrapper = digest.into();
    let mut ret = remexec_api::DirectoryNode::new();
    ret.set_name(name);
    ret.set_digest(digest_wrapper.into());
    ret
  }
}

#[derive(Debug)]
pub struct MerkleTrieNode {
  pub files: Vec<FileNode>,
  pub directories: Vec<DirectoryNode>,
}
impl From<remexec_api::Directory> for MerkleTrieNode {
  fn from(dir: remexec_api::Directory) -> Self {
    let remexec_api::Directory {
      files, directories, ..
    } = dir;
    MerkleTrieNode {
      files: files.into_iter().map(|n| n.into()).collect(),
      directories: directories.into_iter().map(|n| n.into()).collect(),
    }
  }
}
impl Into<remexec_api::Directory> for MerkleTrieNode {
  fn into(self: Self) -> remexec_api::Directory {
    let MerkleTrieNode { files, directories } = self;
    let mut ret = remexec_api::Directory::new();
    ret.set_files(protobuf::RepeatedField::from_vec(
      files.into_iter().map(|n| n.into()).collect(),
    ));
    ret.set_directories(protobuf::RepeatedField::from_vec(
      directories.into_iter().map(|n| n.into()).collect(),
    ));
    ret
  }
}
impl MerkleTrieNode {
  fn decode_utf8(component: merkle_trie::SinglePathComponent) -> Result<String, RemexecError> {
    let bytes = component.extract_component_bytes();
    str::from_utf8(&bytes)
      .map(|p| p.to_string())
      .map_err(|e| RemexecError::from(format!("error encoding path {:?} as utf8: {:?}", bytes, e)))
  }

  pub fn recursively_upload_trie(
    trie: merkle_trie::MerkleTrie<ShmKey>,
  ) -> BoxFuture<DirectoryDigest, RemexecError> {
    let mut files: Vec<(merkle_trie::SinglePathComponent, ShmKey)> = Vec::new();
    let mut sub_tries: Vec<(
      merkle_trie::SinglePathComponent,
      merkle_trie::MerkleTrie<ShmKey>,
    )> = Vec::new();

    for (path_component, entry) in trie.extract_mapping().into_iter() {
      match entry {
        merkle_trie::MerkleTrieEntry::File(key) => files.push((path_component, key)),
        merkle_trie::MerkleTrieEntry::SubTrie(sub_trie) => {
          sub_tries.push((path_component, sub_trie))
        }
      }
    }

    let mapped_file_nodes: Vec<BoxFuture<FileNode, RemexecError>> = files
      .into_iter()
      .map(|(component, key)| {
        let retrieve_result: Result<ShmHandle, RemexecError> =
          ShmHandle::new(ShmRetrieveRequest { key }.into())
            .map_err(|e| RemexecError::InternalError(format!("{:?}", e)));

        future::result(retrieve_result)
          .and_then(|handle| {
            LOCAL_STORE
              .store_file_bytes(Bytes::from(handle.data), true)
              .map_err(|e| RemexecError::InternalError(format!("{:?}", e)))
          })
          .and_then(move |digest| {
            let expected_digest: Digest = key.into();
            let result = if digest != expected_digest {
              Err(RemexecError::InternalError(format!(
                "expected uploaded digest {:?} to be equal to input shm key {:?}",
                digest, key,
              )))
            } else {
              Self::decode_utf8(component).map(|name| FileNode {
                name,
                key,
                permissions: FilePermissions::None,
              })
            };
            dbg!(&result);
            future::result(result)
          })
          .to_boxed()
      })
      .collect();

    /* Recursion!!! */
    let mapped_dir_nodes: Vec<BoxFuture<DirectoryNode, _>> = sub_tries
      .into_iter()
      .map(|(component, sub_trie)| {
        let decoded: Result<String, _> = Self::decode_utf8(component);
        let serialized_directory: BoxFuture<DirectoryDigest, _> =
          Self::recursively_upload_trie(sub_trie)
            .map(|d| d.into())
            .to_boxed();
        future::result(decoded)
          .join(serialized_directory)
          .map(|(name, directory_digest)| DirectoryNode {
            name,
            digest: directory_digest,
          })
          .to_boxed()
      })
      .collect::<Vec<_>>();

    let directory_proto: BoxFuture<remexec_api::Directory, _> = future::join_all(mapped_file_nodes)
      .join(future::join_all(mapped_dir_nodes))
      .map(|(files, directories)| MerkleTrieNode { files, directories })
      .map(|node| node.into())
      .to_boxed();

    let directory_digest: BoxFuture<DirectoryDigest, _> = directory_proto
      .and_then(|directory_proto| {
        (*LOCAL_STORE)
          .record_directory(&directory_proto, true)
          .map_err(|e| e.into())
          .map(|d| d.into())
      })
      .to_boxed();

    directory_digest
  }
}

pub fn expand_directory(digest: Digest) -> BoxFuture<Vec<FileContent>, String> {
  LOCAL_STORE.contents_for_directory(digest, PANTS_WORKUNIT_STORE.clone())
}

pub fn memory_map_file_content(bytes: &[u8]) -> Result<ShmHandle<'static>, RemexecError> {
  let digest = Digest::of_bytes(bytes);
  let key: ShmKey = digest.into();
  dbg!(key);
  let source: *const os::raw::c_void =
    unsafe { mem::transmute::<*const u8, *const os::raw::c_void>(bytes.as_ptr()) };
  let request = ShmAllocateRequest { key, source };
  ShmHandle::new(request.into()).map_err(|e| format!("{:?}", e).into())
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::merkle_trie::{tests::*, *};

  #[test]
  fn directory_upload_expand_end_to_end() -> Result<(), RemexecError> {
    let input: Vec<(PathComponents, &str)> = generate_example_input();

    let mmapped_input: Vec<(PathComponents, ShmKey)> = input
      .iter()
      .map(|(c, s)| {
        (
          c.clone(),
          memory_map_file_content(s.as_bytes()).unwrap().key,
        )
      })
      .collect();

    let file_stats = make_file_stats(&mmapped_input);

    let mut trie = MerkleTrie::<ShmKey>::new();
    trie.populate(file_stats).unwrap();

    let digest: DirectoryDigest =
      block_on_with_persistent_runtime(MerkleTrieNode::recursively_upload_trie(trie))?;
    let file_contents: Vec<FileContent> =
      block_on_with_persistent_runtime(expand_directory(digest.into()))?;

    let output: Vec<(PathComponents, &str)> = file_contents
      .iter()
      .map(|FileContent { path, content, .. }| {
        (
          PathComponents::from_path(&path).unwrap(),
          str::from_utf8(&content).unwrap(),
        )
      })
      .collect();
    assert_eq!(input, output);
    Ok(())
  }
}
