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

use memory::shm::{CCharErrorMessage, ShmKey};

use bazel_protos::remote_execution as remexec;
use boxfuture::{BoxFuture, Boxable};
use hashing::{Digest, Fingerprint};
use store::Store;
use task_executor::Executor;

use futures01::{future, Future};
use indexmap::IndexMap;
use lazy_static::lazy_static;
use protobuf;

use std::convert::{From, Into};
use std::env;
use std::ffi::OsStr;
use std::mem;
use std::os::{self, unix::ffi::OsStrExt};
use std::path::{Path, PathBuf};
use std::slice;
use std::sync::Arc;

lazy_static! {
  static ref LOCAL_STORE_PATH: PathBuf = match env::var("UPC_IN_PROCESS_LOCAL_STORE_DIR").ok() {
    Some(local_store_dir) => PathBuf::from(local_store_dir),
    None => PathBuf::from(env::var("HOME").unwrap()).join(".cache/pants/lmdb_store"),
  };
  static ref PANTS_TOKIO_EXECUTOR: Executor = Executor::new();
  static ref LOCAL_STORE: Arc<Store> = {
    let executor = PANTS_TOKIO_EXECUTOR.clone();
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

unsafe impl Send for ExpandDirectoriesRequest {}
unsafe impl Sync for ExpandDirectoriesRequest {}

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
    &ChildRelPath {
      relpath,
      relpath_size: slice.len() as u64,
    }
  }
}

/* FIXME: remove all directories from path stats!!! all path stats *strictly* just contain file
 * paths!! directory paths are *INFERRED*!!!! */
#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct FileStat {
  pub rel_path: ChildRelPath,
  pub key: ShmKey,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct PathStats {
  stats: *const FileStat,
  num_stats: u64,
}
impl PathStats {
  pub fn from_slice(stats: &[FileStat]) -> &Self {
    let num_stats = stats.len();
    &PathStats {
      stats: stats.as_ptr(),
      num_stats: num_stats as u64,
    }
  }
  pub unsafe fn as_slice(&self) -> &[FileStat] {
    slice::from_raw_parts(self.stats, self.num_stats as usize)
  }
}
unsafe impl Send for PathStats {}
unsafe impl Sync for PathStats {}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum FilePermissions {
  Executable,
  None,
}

/* Necessary to mediate conversions between the remexec digest our own digests. */
struct RemexecDigestWrapper {
  pub fingerprint: Fingerprint,
  pub size_bytes: usize,
}
impl From<remexec::Digest> for RemexecDigestWrapper {
  fn from(digest: remexec::Digest) -> Self {
    RemexecDigestWrapper {
      fingerprint: Fingerprint::from_hex_string(&digest.hash).unwrap(),
      size_bytes: digest.size_bytes as usize,
    }
  }
}
impl Into<remexec::Digest> for RemexecDigestWrapper {
  fn into(self: Self) -> remexec::Digest {
    let mut ret = remexec::Digest::new();
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
impl From<remexec::FileNode> for FileNode {
  fn from(node: remexec::FileNode) -> Self {
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
impl Into<remexec::FileNode> for FileNode {
  fn into(self: Self) -> remexec::FileNode {
    let FileNode {
      name,
      key,
      permissions,
    } = self;
    let mut ret = remexec::FileNode::new();
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
impl From<remexec::DirectoryNode> for DirectoryNode {
  fn from(node: remexec::DirectoryNode) -> Self {
    let digest_wrapper: RemexecDigestWrapper = node.get_digest().clone().into();
    let digest: Digest = digest_wrapper.into();
    DirectoryNode {
      name: node.get_name().to_string(),
      digest: digest.into(),
    }
  }
}
impl Into<remexec::DirectoryNode> for DirectoryNode {
  fn into(self: Self) -> remexec::DirectoryNode {
    let DirectoryNode { name, digest } = self;
    let digest: Digest = digest.into();
    let digest_wrapper: RemexecDigestWrapper = digest.into();
    let mut ret = remexec::DirectoryNode::new();
    ret.set_name(name);
    ret.set_digest(digest_wrapper.into());
    ret
  }
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
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
      Ok(PathComponent { component })
    }
  }

  pub fn extract_component_string(self) -> String {
    self.component
  }
}

#[derive(Debug)]
struct MerkleTrie {
  map: IndexMap<PathComponent, MerkleTrieEntry>,
}
impl MerkleTrie {
  pub fn new() -> Self {
    MerkleTrie {
      map: IndexMap::new(),
    }
  }

  pub fn extract_mapping(self) -> IndexMap<PathComponent, MerkleTrieEntry> {
    self.map
  }

  pub fn from(map: IndexMap<PathComponent, MerkleTrieEntry>) -> Self {
    MerkleTrie { map }
  }

  pub fn advance_path(
    &mut self,
    rel_path: &Path,
    terminal: MerkleTrieTerminalEntry,
  ) -> Result<(), DirectoryFFIError> {
    assert!(rel_path.is_relative());

    let all_components: Vec<PathComponent> = rel_path
      .components()
      .map(|component| PathComponent::from(component.as_os_str().to_string_lossy().to_string()))
      .collect::<Result<Vec<PathComponent>, DirectoryFFIError>>()?;

    match all_components.split_last() {
      None => unreachable!(),
      Some((last_component, intermediate_components)) => {
        let mut cur_trie: &mut Self = &mut self;

        /* Get the penultimate trie. */
        for component in intermediate_components.iter() {
          let cur_trie_entry = cur_trie
            .map
            .entry(*component)
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
          }
        }

        let last_component = last_component.clone();
        if let Some(existing_entry) = cur_trie.map.get(&last_component) {
          Err(DirectoryFFIError::OverlappingPathStats(format!(
            "found existing entry {:?} in cur trie {:?} -- should be empty (no file should have the same path in a set of path stats!)!",
            existing_entry, cur_trie,
          )))
        } else {
          let to_insert: MerkleTrieEntry = match terminal {
            MerkleTrieTerminalEntry::File(key) => MerkleTrieEntry::File(key),
          };
          cur_trie.map.insert(last_component, to_insert);
          Ok(())
        }
      }
    }
  }

  pub fn populate(&mut self, path_stats: &[FileStat]) -> Result<(), DirectoryFFIError> {
    for FileStat { rel_path, key } in path_stats.iter() {
      self.advance_path(
        unsafe { rel_path.as_path() },
        MerkleTrieTerminalEntry::File(*key),
      )?;
    }
    Ok(())
  }
}

#[derive(Debug)]
enum MerkleTrieEntry {
  File(ShmKey),
  SubTrie(MerkleTrie),
}

/* This distinct enum is useful, even if there is only one case! */
enum MerkleTrieTerminalEntry {
  File(ShmKey),
}

#[derive(Debug)]
pub struct MerkleTrieNode {
  pub files: Vec<FileNode>,
  pub directories: Vec<DirectoryNode>,
}
impl From<remexec::Directory> for MerkleTrieNode {
  fn from(dir: remexec::Directory) -> Self {
    let remexec::Directory {
      files, directories, ..
    } = dir;
    MerkleTrieNode {
      files: files.into_iter().map(|n| n.into()).collect(),
      directories: directories.into_iter().map(|n| n.into()).collect(),
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
  pub fn recursively_upload_trie(
    trie: MerkleTrie,
  ) -> BoxFuture<DirectoryDigest, DirectoryFFIError> {
    let mut files: Vec<(PathComponent, ShmKey)> = Vec::new();
    let mut sub_tries: Vec<(PathComponent, MerkleTrie)> = Vec::new();

    for (path_component, entry) in trie.extract_mapping().into_iter() {
      match entry {
        MerkleTrieEntry::File(key) => files.push((path_component, key)),
        MerkleTrieEntry::SubTrie(sub_trie) => sub_tries.push((path_component, sub_trie)),
      }
    }

    let mapped_file_nodes: Vec<FileNode> = files
      .into_iter()
      .map(|(component, key)| FileNode {
        name: component.extract_component_string(),
        key,
        permissions: FilePermissions::None,
      })
      .collect();

    /* Recursion!!! */
    let mapped_dir_nodes: BoxFuture<Vec<DirectoryNode>, _> = future::join_all(
      sub_tries
        .into_iter()
        .map(|(component, sub_trie)| {
          let serialized_directory: BoxFuture<DirectoryDigest, _> =
            Self::recursively_upload_trie(sub_trie)
              .map(|d| d.into())
              .to_boxed();
          serialized_directory
            .map(|directory_digest| DirectoryNode {
              name: component.extract_component_string(),
              digest: directory_digest,
            })
            .to_boxed()
        })
        .collect::<Vec<_>>(),
    )
    .to_boxed();

    let directory_proto: BoxFuture<remexec::Directory, _> = mapped_dir_nodes
      .map(|directories| MerkleTrieNode {
        files: mapped_file_nodes,
        directories,
      })
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

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ExpandDirectoriesMapping {
  digests: *mut DirectoryDigest,
  expansions: *mut PathStats,
  num_expansions: u64,
}
impl ExpandDirectoriesMapping {
  pub fn from_slices<'a>(
    digests: &'a [DirectoryDigest],
    expansions: &'a [PathStats],
  ) -> Result<&'a Self, DirectoryFFIError> {
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
      let boxed_digests: Box<[DirectoryDigest]> = Box::from(digests);
      let boxed_expansions: Box<[PathStats]> = Box::from(expansions);
      Ok(&ExpandDirectoriesMapping {
        digests: Box::into_raw(boxed_digests) as *mut DirectoryDigest,
        expansions: Box::into_raw(boxed_expansions) as *mut PathStats,
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
unsafe impl Send for ExpandDirectoriesMapping {}
unsafe impl Sync for ExpandDirectoriesMapping {}

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
  UploadDirectoriesSucceeded(ExpandDirectoriesMapping),
  UploadDirectoriesFailed(*mut os::raw::c_char),
}

fn directories_upload_single(
  file_stats: &[FileStat],
) -> BoxFuture<DirectoryDigest, DirectoryFFIError> {
  let mut trie = MerkleTrie::new();
  future::result(trie.populate(file_stats))
    .and_then(|()| MerkleTrieNode::recursively_upload_trie(trie))
    .to_boxed()
}

fn directories_upload_impl(
  all_path_stats: &[&[FileStat]],
) -> BoxFuture<ExpandDirectoriesMapping, DirectoryFFIError> {
  let expansions: Vec<PathStats> = all_path_stats
    .iter()
    .map(|stats| PathStats::from_slice(stats))
    .cloned()
    .collect();

  let upload_tasks: Vec<BoxFuture<DirectoryDigest, _>> = all_path_stats
    .iter()
    .map(|file_stats| directories_upload_single(file_stats))
    .collect();
  let digests: BoxFuture<Vec<DirectoryDigest>, _> = future::join_all(upload_tasks).to_boxed();

  digests
    .and_then(|digests| {
      future::result(
        ExpandDirectoriesMapping::from_slices(&digests, &expansions).map(|mapping| mapping.clone()),
      )
      .to_boxed()
    })
    .to_boxed()
}

#[no_mangle]
pub unsafe extern "C" fn directories_upload(
  request: UploadDirectoriesRequest,
) -> UploadDirectoriesResult {
  let path_stats: &[PathStats] = request.as_slice();
  let all_path_stats: Vec<&[FileStat]> = path_stats.iter().map(|stats| stats.as_slice()).collect();
  let result: Result<ExpandDirectoriesMapping, _> =
    PANTS_TOKIO_EXECUTOR.block_on_with_persistent_runtime(directories_upload_impl(&all_path_stats));
  match result {
    Ok(mapping) => UploadDirectoriesResult::UploadDirectoriesSucceeded(mapping),
    Err(e) => {
      let error_message = CCharErrorMessage::new(format!("{:?}", e));
      UploadDirectoriesResult::UploadDirectoriesFailed(
        error_message.leak_null_terminated_c_string(),
      )
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
