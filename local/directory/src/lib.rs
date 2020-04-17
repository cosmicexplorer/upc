#![deny(warnings)]
// Enable all clippy lints except for many of the pedantic ones. It's a shame this needs to be copied and pasted across crates, but there doesn't appear to be a way to include inner attributes from a common source.
#![deny(
  clippy::all,
  clippy::default_trait_access,
  clippy::expl_impl_clone_on_copy,
  clippy::if_not_else,
  clippy::needless_continue,
  clippy::unseparated_literal_suffix,
  clippy::used_underscore_binding
)]
// It is often more clear to show that nothing is being moved.
#![allow(clippy::match_ref_pats)]
// Subjective style.
#![allow(
  clippy::len_without_is_empty,
  clippy::redundant_field_names,
  clippy::too_many_arguments
)]
// Default isn't as big a deal as people seem to think it is.
#![allow(clippy::new_without_default, clippy::new_ret_no_self)]
// Arc<Mutex> can be more clear than needing to grok Orderings:
#![allow(clippy::mutex_atomic)]

use memory::shm::{ShmHandle, ShmKey};

use bazel_protos::remote_execution as remexec;
use boxfuture::{BoxFuture, Boxable};
use hashing::{Digest, Fingerprint};
use store::Store;
use task_executor::Executor;

use bytes::Bytes;
use futures::{
  channel::oneshot,
  future::{self, FutureExt},
};
use indexmap::IndexMap;
use lazy_static::lazy_static;
use protobuf;

use std::convert::{From, Into};
use std::env;
use std::ffi::OsStr;
use std::future::Future;
use std::mem;
use std::os::{self, unix::ffi::OsStrExt};
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::slice;
use std::sync::Arc;
use std::task::{Context, Poll, Waker};

lazy_static! {
  static ref LOCAL_STORE_PATH: PathBuf = match env::var("UPC_IN_PROCESS_LOCAL_STORE_DIR").ok() {
    Some(local_store_dir) => PathBuf::from(local_store_dir),
    None => PathBuf::from(env::var("HOME").unwrap()).join(".cache/pants/lmdb_store"),
  };
  static ref LOCAL_STORE: Arc<Store> = {
    let executor = Executor::new();
    let store = Store::local_only(executor, &*LOCAL_STORE_PATH).unwrap();
    Arc::new(store)
  };
}

#[derive(Debug)]
pub enum DirectoryFFIError {
  InternalError(String),
  OverlappingPathStats(String),
}

impl From<String> for DirectoryFFIError {
  fn from(err: String) -> Self {
    DirectoryFFIError::InternalError(err)
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct DirectoryDigest {
  pub fingerprint: Fingerprint,
  pub size_bytes: u64,
}

impl From<Digest> for DirectoryDigest {
  fn from(digest: Digest) -> Self {
    let Digest(fingerprint, size_bytes) = digest;
    DirectoryDigest {
      fingerprint,
      size_bytes: size_bytes as u64,
    }
  }
}

impl Into<Digest> for DirectoryDigest {
  fn into(self: Self) -> Digest {
    let DirectoryDigest {
      fingerprint,
      size_bytes,
    } = self;
    Digest(fingerprint, size_bytes as usize)
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ExpandDirectoriesRequest {
  pub requests: *const DirectoryDigest,
  pub num_requests: u64,
}
impl ExpandDirectoriesRequest {
  pub unsafe fn as_slice(&self) -> &[DirectoryDigest] {
    slice::from_raw_parts(self.requests, self.num_requests as usize)
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ChildRelPath {
  relpath: *const os::raw::c_char,
  relpath_size: u64,
}
impl ChildRelPath {
  pub unsafe fn as_path(&self) -> &Path {
    let bytes_ptr: *const u8 = mem::transmute::<*const os::raw::c_char, *const u8>(self.relpath);
    let slice: &[u8] = slice::from_raw_parts(bytes_ptr, self.relpath_size as usize);
    Path::new(OsStr::from_bytes(slice))
  }
  pub unsafe fn from_path(path: &Path) -> &Self {
    let slice: &[u8] = path.as_os_str().as_bytes();
    let relpath: *const os::raw::c_char =
      mem::transmute::<*const u8, *const os::raw::c_char>(slice.as_ptr());
    ChildRelPath {
      relpath,
      relpath_size: slice.len() as u64,
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub enum SinglePathStat {
  FileStat(ChildRelPath, ShmKey),
  DirectoryStat(ChildRelPath, DirectoryDigest),
}
impl SinglePathStat {
  pub unsafe fn as_safe(&self) -> &SafePathStat {
    match self {
      Self::FileStat(rel_path, key) => SafePathStat::FileStat(rel_path.as_path(), key),
      Self::DirectoryStat(rel_path, digest) => {
        SafePathStat::DirectoryStat(rel_path.as_path(), digest)
      }
    }
  }
}

pub enum SafePathStat {
  FileStat(Path, ShmKey),
  DirectoryStat(Path, DirectoryDigest),
}
impl SafePathStat {
  pub unsafe fn as_native(&self) -> &SinglePathStat {
    match self {
      Self::FileStat(path, key) => SinglePathStat::FileStat(ChildRelPath::from_path(path), key),
      Self::DirectoryStat(path, digest) => {
        SinglePathStat::DirectoryStat(ChildRelPath::from_path(path), digest)
      }
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct PathStats {
  stats: *const SinglePathStat,
  num_stats: u64,
}
impl PathStats {
  pub fn from_slice(stats: &[SinglePathStat]) -> &Self {
    let num_stats = stats.len();
    PathStats {
      stats: stats.as_ptr(),
      num_stats: num_stats as u64,
    }
  }
  pub unsafe fn safe_slice(&self) -> &[SafePathStat] {
    slice::from_raw_parts(self.stats, self.num_stats as usize)
  }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum FilePermissions {
  Executable,
  None,
}

#[derive(Debug)]
pub struct FileNode {
  pub name: String,
  pub key: ShmKey,
  pub permissions: FilePermissions,
}
impl From<remexec::FileNode> for FileNode {
  fn from(node: remexec::FileNode) -> Self {
    FileNode {
      name: node.get_name().to_string(),
      key: node.get_digest().into(),
      permissions: match node.get_is_executable {
        true => FilePermissions::Executable,
        false => FilePermissions::None,
      },
    }
  }
}
impl Into<remexec::FileNode> for FileNode {
  fn into(self: Self) -> remexec::FileNode {
    let FileNode {
      name,
      key,
      permissions,
    } = self;
    let mut ret = remexec::FileNode::new();
    ret.set_name(name);
    ret.set_digest(key.into());
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
impl From<remexec::DirectoryNode> for DirectoryNode {
  fn from(node: remexec::DirectoryNode) -> Self {
    DirectoryNode {
      name: node.get_name().to_string(),
      digest: node.get_digest().into(),
    }
  }
}
impl Into<remexec::DirectoryNode> for DirectoryNode {
  fn into(self: Self) -> remexec::DirectoryNode {
    let DirectoryNode { name, digest } = self;
    let mut ret = remexec::DirectoryNode::new();
    ret.set_name(name);
    ret.set_digest(digest.into());
    ret
  }
}

struct PathComponent {
  component: String,
}
impl PathComponent {
  pub fn from(component: String) -> Result<Self, DirectoryFFIError> {
    if component.find(std::path::MAIN_SEPARATOR).is_some() {
      Err(
        format!(
          "invalid path component: {:?} (contained separator)",
          component
        )
        .into(),
      )
    } else {
      PathComponent { component }
    }
  }
}

struct MerkleTrie {
  map: IndexMap<PathComponent, MerkleTrieEntry>,
}
impl MerkleTrie {
  pub fn new() -> Self {
    MerkleTrie {
      map: IndexMap::new(),
    }
  }

  pub fn from(map: IndexMap<PathComponent, MerkleTrieEntry>) -> Self {
    MerkleTrie { map }
  }

  pub fn advance_path(
    &mut self,
    rel_path: &Path,
    terminal: MerkleTrieTerminalEntry,
  ) -> Result<(), DirectoryFFIError> {
    if !rel_path.is_relative() {
      return Err(
        format!(
          "path {:?} was expected to be relative when indexing into merkle trie {:?}",
          rel_path, self,
        )
        .into(),
      );
    }

    let all_components: Vec<PathComponent> = rel_path
      .components()
      .map(PathComponent::from)
      .collect::<Result<Vec<PathComponent>, DirectoryFFIError>>(
    )?;

    match all_components.split_last() {
      None => Err(
        format!(
          "path {:?} without any components provided when indexing into merkle trie {:?}",
          rel_path, self,
        )
        .into(),
      ),
      Some((last_component, intermediate_components)) => {
        let mut cur_trie: &mut Self = &mut self;

        /* Get the penultimate trie. */
        for component in intermediate_components.iter() {
          let cur_trie_entry = cur_trie
            .map
            .entry(component)
            .or_insert_with(|| MerkleTrieEntry::SubTrie(MerkleTrie::new()));
          match &mut *cur_trie_entry {
            MerkleTrieEntry::SubTrie(mut sub_trie) => {
              cur_trie = &mut sub_trie;
            }
            MerkleTrieEntry::File(key) => {
              return Err(DirectoryFFIError::OverlappingPathStats(format!(
                "expected sub trie at entry {:?}: got file for key {:?} instead!",
                *cur_trie_entry, key,
              )));
            }
            MerkleTrieEntry::Directory(digest) => {
              return Err(DirectoryFFIError::OverlappingPathStats(format!(
                "expected sub trie at entry {:?}: got directory for digest {:?} instead!",
                *cur_trie_entry, digest,
              )));
            }
          }
        }

        if let Some(existing_entry) = cur_trie.map.get(&last_component) {
          Err(DirectoryFFIError::OverlappingPathStats(format!(
            "found existing entry {:?} in cur trie {:?} -- should be empty!",
            existing_entry, cur_trie,
          )))
        } else {
          let to_insert: MerkleTrieEntry = match terminal {
            MerkleTrieTerminalEntry::File(key) => MerkleTrieEntry::File(key),
            MerkleTrieTerminalEntry::Directory(digest) => MerkleTrieEntry::Directory(digest),
          };
          cur_trie.map.insert(&last_component, to_insert);
        }
        let final_entry = cur_trie
          .map
          .entry(last_component)
          .or_insert_with(|| MerkleTrieEntry::SubTrie(MerkleTrie::new()));
      }
    }
  }

  pub fn populate(&mut self, path_stats: &[SafePathStat]) -> Result<(), DirectoryFFIError> {
    for stat in path_stats.iter() {
      match stat {
        SafePathStat::FileStat(rel_path, key) => {
          self.advance_path(rel_path, MerkleTrieTerminalEntry::File(key))?;
        }
        SafePathStat::DirectoryStat(rel_path, digest) => {
          self.advance_path(rel_path, MerkleTrieTerminalEntry::Directory(digest))?;
        }
      }
    }
  }
}

enum MerkleTrieEntry {
  File(ShmKey),
  SubTrie(MerkleTrie),
}

enum MerkleTrieTerminalEntry {
  File(ShmKey),
  Directory(DirectoryDigest),
}

#[derive(Debug)]
pub struct MerkleTrieNode {
  pub files: Vec<FileNode>,
  pub directories: Vec<DirectoryNode>,
}
impl From<remexec::Directory> for MerkleTrieNode {
  fn from(dir: remexec::Directory) -> Self {
    MerkleTrieNode {
      files: dir.get_files().iter().map(|n| n.into()).collect(),
      directories: dir.get_directories().iter().map(|n| n.into()).collect(),
    }
  }
}
impl Into<remexec::Directory> for MerkleTrieNode {
  fn into(self: Self) -> remexec::Directory {
    let MerkleTrieNode { files, directories } = self;
    let mut ret = remexec::Directory::new();
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
  pub async fn recursively_upload_trie(trie: MerkleTrie) -> Result<Self, DirectoryFFIError> {
    let directory_stack: Vec<p>
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ExpandDirectoriesMapping {
  digests: *const DirectoryDigest,
  expansions: *const PathStats,
  num_expansions: u64,
}
impl ExpandDirectoriesMapping {
  pub fn from_slices(
    digests: &[DirectoryDigest],
    expansions: &[PathStats],
  ) -> Result<&Self, DirectoryFFIError> {
    let num_digests = digests.len();
    let num_expansions = expansions.len();
    if num_digests != num_expansions {
      Err(
        format!(
          "slices for digests (length {:?}) and expansions (length {:?}) were not the same!",
          num_digests, num_expansions,
        )
        .into(),
      )
    } else {
      Ok(ExpandDirectoriesMapping {
        digests: digests.as_ptr(),
        expansions: expansions.as_ptr(),
        num_expansions: num_expansions as u64,
      })
    }
  }
  pub unsafe fn as_slices(&self) -> (&[DirectoryDigest], &[PathStats]) {
    (
      slice::from_raw_parts(self.digests, self.num_expansions as usize),
      slice::from_raw_parts(self.expansions, self.num_expansions as usize),
    )
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub enum ExpandDirectoriesResult {
  ExpandDirectoriesSucceeded(ExpandDirectoriesMapping),
  ExpandDirectoriesFailed(*mut os::raw::c_char),
}

#[no_mangle]
pub unsafe extern "C" fn directories_expand(
  request: ExpandDirectoriesRequest,
) -> ExpandDirectoriesResult {
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct UploadDirectoriesRequest {
  path_stats: *const PathStats,
  num_path_stats: u64,
}
impl UploadDirectoriesRequest {
  pub unsafe fn as_slice(&self) -> &[PathStats] {
    slice::from_raw_parts(self.path_stats, self.num_path_stats as usize)
  }
}

#[repr(C)]
pub enum UploadDirectoriesResult {
  UploadDirectoriesSucceeded,
  UploadDirectoriesFailed(*mut os::raw::c_char),
}

#[no_mangle]
pub unsafe extern "C" fn directories_upload(
  request: ExpandDirectoriesMapping,
) -> UploadDirectoriesResult {
  let (digests, expansions) = request.as_slices();
  for (digest, path_stats) in digests
    .iter()
    .zip(expansions.iter().map(|path_stats| path_stats.as_slice()))
  {
    for stat in path_stats.iter() {
      match stat {
        SinglePathStat::FileStat() => (),
        SinglePathStat::DirectoryStat() => (),
      }
    }
  }
}

#[cfg(test)]
mod tests {
  #[test]
  fn it_works() {
    assert_eq!(2 + 2, 4);
  }
}
