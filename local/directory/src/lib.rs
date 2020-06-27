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
#![allow(clippy::redundant_field_names, clippy::too_many_arguments)]
// Default isn't as big a deal as people seem to think it is.
#![allow(clippy::new_without_default, clippy::new_ret_no_self)]
// Arc<Mutex> can be more clear than needing to grok Orderings:
#![allow(clippy::mutex_atomic)]

pub mod merkle_trie;
pub mod remexec;

pub use memory::shm::SizeType;
use memory::shm::*;

use bazel_protos::remote_execution as bazel_remexec;
use boxfuture::{BoxFuture, Boxable};
use fs::FileContent;
use hashing::{Digest, Fingerprint};
use sharded_lmdb::ShardedLmdb;

use futures01::{future, Future};
use indexmap::IndexMap;
use lazy_static::lazy_static;
use protobuf;
use serde_json;

use std::convert::{From, Into};
use std::default::Default;
use std::ffi::{CStr, CString, OsStr};
use std::fmt::Debug;
use std::mem;
use std::os::{self, unix::ffi::OsStrExt};
use std::path::PathBuf;
use std::ptr;
use std::slice;
use std::str;
use std::sync::Arc;

lazy_static! {
  static ref GIT_OID_MAPPING_STORE_PATH: PathBuf = LOCAL_STORE_PATH.join("git-oid-mapping");
  static ref GIT_OID_DB: Arc<ShardedLmdb> = Arc::new(
    ShardedLmdb::new(
      GIT_OID_MAPPING_STORE_PATH.clone(),
      1_000_000,
      PANTS_TOKIO_EXECUTOR.clone()
    )
    .expect("failed to create ShardedLmdb for git-oid-mapping")
  );
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
impl From<remexec::RemexecError> for DirectoryFFIError {
  fn from(err: remexec::RemexecError) -> Self {
    DirectoryFFIError::InternalError(format!("{:?}", err))
  }
}
impl From<merkle_trie::MerkleTrieError> for DirectoryFFIError {
  fn from(err: merkle_trie::MerkleTrieError) -> Self {
    match err {
      merkle_trie::MerkleTrieError::OverlappingPathStats(e) => {
        DirectoryFFIError::OverlappingPathStats(e)
      }
      merkle_trie::MerkleTrieError::InternalError(e) => DirectoryFFIError::InternalError(e),
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq, Hash)]
pub struct DirectoryDigest {
  pub size_bytes: SizeType,
  pub fingerprint: Fingerprint,
}
impl From<Digest> for DirectoryDigest {
  fn from(digest: Digest) -> Self {
    let Digest(fingerprint, size_bytes) = digest;
    DirectoryDigest {
      fingerprint,
      size_bytes: size_bytes as SizeType,
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
impl Default for DirectoryDigest {
  fn default() -> Self {
    DirectoryDigest {
      size_bytes: 0,
      fingerprint: hashing::EMPTY_FINGERPRINT,
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct ExpandDirectoriesRequest {
  pub num_requests: SizeType,
  pub requests: *const DirectoryDigest,
}

pub trait NativeWrapper<T> {
  unsafe fn from_ffi(req: T) -> Self;
  fn into_ffi(self) -> T;
}

#[derive(Debug, Clone)]
pub struct HighLevelExpandDirectoriesRequest {
  pub requests: Vec<DirectoryDigest>,
}
impl NativeWrapper<ExpandDirectoriesRequest> for HighLevelExpandDirectoriesRequest {
  unsafe fn from_ffi(req: ExpandDirectoriesRequest) -> Self {
    let ExpandDirectoriesRequest {
      num_requests,
      requests,
    } = req;
    HighLevelExpandDirectoriesRequest {
      requests: slice::from_raw_parts(requests, num_requests as usize).to_vec(),
    }
  }
  fn into_ffi(self) -> ExpandDirectoriesRequest {
    let HighLevelExpandDirectoriesRequest { requests } = self;
    let ret = ExpandDirectoriesRequest {
      requests: requests.as_ptr(),
      num_requests: requests.len() as SizeType,
    };
    mem::forget(requests);
    ret
  }
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ChildRelPath {
  relpath_size: SizeType,
  relpath: *const os::raw::c_char,
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub struct HighLevelChildRelPath {
  pub path: PathBuf,
}
impl NativeWrapper<ChildRelPath> for HighLevelChildRelPath {
  unsafe fn from_ffi(path: ChildRelPath) -> Self {
    let ChildRelPath {
      relpath_size,
      relpath,
    } = path;
    let bytes_ptr: *const u8 = mem::transmute::<*const os::raw::c_char, *const u8>(relpath);
    let slice: &[u8] = slice::from_raw_parts(bytes_ptr, relpath_size as usize);
    HighLevelChildRelPath {
      path: PathBuf::from(OsStr::from_bytes(slice)),
    }
  }
  fn into_ffi(self) -> ChildRelPath {
    let HighLevelChildRelPath { path } = self;
    let (len, path_str) = {
      let os_str = path.into_os_string();
      (os_str.len(), CString::new(os_str.as_bytes()).unwrap())
    };

    ChildRelPath {
      relpath_size: len as SizeType,
      relpath: path_str.into_raw(),
    }
  }
}

/* NB: we remove all directories from path stats!!! all path stats *strictly* just contain file
 * paths!! directory paths are *INFERRED*!!!! */
#[repr(C)]
#[derive(Copy, Clone)]
pub struct FileStat {
  pub key: ShmKey,
  pub rel_path: ChildRelPath,
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub struct HighLevelFileStat {
  pub key: ShmKey,
  pub rel_path: HighLevelChildRelPath,
}
impl NativeWrapper<FileStat> for HighLevelFileStat {
  unsafe fn from_ffi(file_stat: FileStat) -> Self {
    let FileStat { key, rel_path } = file_stat;
    HighLevelFileStat {
      key,
      rel_path: HighLevelChildRelPath::from_ffi(rel_path),
    }
  }
  fn into_ffi(self) -> FileStat {
    let HighLevelFileStat { key, rel_path } = self;
    FileStat {
      key,
      rel_path: rel_path.into_ffi(),
    }
  }
}
impl Into<merkle_trie::FileStat<ShmKey>> for HighLevelFileStat {
  fn into(self: Self) -> merkle_trie::FileStat<ShmKey> {
    let HighLevelFileStat { rel_path, key } = self;
    let components = merkle_trie::PathComponents::from_path(rel_path.path.as_path()).unwrap();
    merkle_trie::FileStat {
      components,
      terminal: merkle_trie::MerkleTrieTerminalEntry::File(key),
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct PathStats {
  num_stats: SizeType,
  stats: *const FileStat,
}

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct HighLevelPathStats {
  pub stats: Vec<HighLevelFileStat>,
}
impl NativeWrapper<PathStats> for HighLevelPathStats {
  unsafe fn from_ffi(path_stats: PathStats) -> Self {
    let PathStats { num_stats, stats } = path_stats;
    let slice: &[FileStat] = slice::from_raw_parts(stats, num_stats as usize);
    HighLevelPathStats {
      stats: slice
        .iter()
        .map(|file_stat| HighLevelFileStat::from_ffi(*file_stat))
        .collect(),
    }
  }
  fn into_ffi(self) -> PathStats {
    let HighLevelPathStats { stats } = self;
    let owned: Vec<FileStat> = stats
      .into_iter()
      .map(|file_stat| file_stat.into_ffi())
      .collect();
    let ret = PathStats {
      num_stats: owned.len() as SizeType,
      stats: owned.as_ptr(),
    };
    mem::forget(owned);
    ret
  }
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ExpandDirectoriesMapping {
  num_expansions: SizeType,
  digests: *const DirectoryDigest,
  expansions: *const PathStats,
}
impl Default for ExpandDirectoriesMapping {
  fn default() -> Self {
    ExpandDirectoriesMapping {
      num_expansions: 0,
      digests: ptr::null(),
      expansions: ptr::null(),
    }
  }
}

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct HighLevelExpandDirectoriesMapping {
  pub mapping: IndexMap<DirectoryDigest, HighLevelPathStats>,
}
impl NativeWrapper<ExpandDirectoriesMapping> for HighLevelExpandDirectoriesMapping {
  unsafe fn from_ffi(mapping: ExpandDirectoriesMapping) -> Self {
    let ExpandDirectoriesMapping {
      num_expansions,
      digests,
      expansions,
    } = mapping;
    let path_stats: &[PathStats] = slice::from_raw_parts(expansions, num_expansions as usize);
    let digests = slice::from_raw_parts(digests, num_expansions as usize);
    HighLevelExpandDirectoriesMapping {
      mapping: digests
        .iter()
        .cloned()
        .zip(
          path_stats
            .iter()
            .map(|path_stats| HighLevelPathStats::from_ffi(*path_stats)),
        )
        .collect(),
    }
  }
  fn into_ffi(self) -> ExpandDirectoriesMapping {
    let HighLevelExpandDirectoriesMapping { mapping } = self;
    let (digests, path_stats): (Vec<DirectoryDigest>, Vec<PathStats>) = mapping.into_iter().fold(
      (vec![], vec![]),
      |(mut digests, mut path_stats), (digest, path_stat)| {
        digests.push(digest);
        path_stats.push(path_stat.into_ffi());
        (digests, path_stats)
      },
    );
    let ret = ExpandDirectoriesMapping {
      num_expansions: digests.len() as SizeType,
      digests: digests.as_ptr(),
      expansions: path_stats.as_ptr(),
    };
    mem::forget(digests);
    mem::forget(path_stats);
    ret
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
pub enum ExpandDirectoriesResultStatus {
  ExpandDirectoriesSucceeded,
  ExpandDirectoriesFailed,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ExpandDirectoriesResult {
  pub mapping: ExpandDirectoriesMapping,
  pub error_message: *mut os::raw::c_char,
  pub status: ExpandDirectoriesResultStatus,
}
impl Default for ExpandDirectoriesResult {
  fn default() -> Self {
    ExpandDirectoriesResult {
      mapping: ExpandDirectoriesMapping::default(),
      error_message: ptr::null_mut(),
      status: ExpandDirectoriesResultStatus::ExpandDirectoriesFailed,
    }
  }
}

#[derive(Debug, Clone)]
pub enum HighLevelExpandDirectoriesResult {
  Successful(HighLevelExpandDirectoriesMapping),
  Failed(CString),
}
impl NativeWrapper<ExpandDirectoriesResult> for HighLevelExpandDirectoriesResult {
  unsafe fn from_ffi(result: ExpandDirectoriesResult) -> Self {
    let ExpandDirectoriesResult {
      mapping,
      error_message,
      status,
    } = result;
    match status {
      ExpandDirectoriesResultStatus::ExpandDirectoriesSucceeded => {
        HighLevelExpandDirectoriesResult::Successful(HighLevelExpandDirectoriesMapping::from_ffi(
          mapping,
        ))
      }
      ExpandDirectoriesResultStatus::ExpandDirectoriesFailed => {
        HighLevelExpandDirectoriesResult::Failed(CString::from_raw(error_message))
      }
    }
  }
  fn into_ffi(self) -> ExpandDirectoriesResult {
    match self {
      Self::Successful(mapping) => ExpandDirectoriesResult {
        mapping: mapping.into_ffi(),
        error_message: ptr::null_mut(),
        status: ExpandDirectoriesResultStatus::ExpandDirectoriesSucceeded,
      },
      Self::Failed(error_message) => ExpandDirectoriesResult {
        mapping: ExpandDirectoriesMapping::default(),
        error_message: error_message.into_raw(),
        status: ExpandDirectoriesResultStatus::ExpandDirectoriesFailed,
      },
    }
  }
}

fn directories_expand_single(
  digest: DirectoryDigest,
) -> BoxFuture<HighLevelPathStats, DirectoryFFIError> {
  let pants_digest: Digest = digest.into();
  let all_files_content: BoxFuture<Vec<FileContent>, String> =
    remexec::expand_directory(pants_digest);

  all_files_content
    .map_err(|e| format!("{:?}", e).into())
    .and_then(|all_files_content| {
      let file_uploads: Result<Vec<ShmHandle>, _> = all_files_content
        .iter()
        .map(|file_content| {
          remexec::memory_map_file_content(file_content.content.as_ref())
            .map_err(|e| DirectoryFFIError::from(format!("{:?}", e)))
        })
        .collect();
      let as_path_stats: Result<HighLevelPathStats, _> = file_uploads.map(|all_handles| {
        let file_stats: Vec<HighLevelFileStat> = all_files_content
          .into_iter()
          .zip(all_handles)
          .map(|(file_content, handle)| HighLevelFileStat {
            key: handle.key,
            rel_path: HighLevelChildRelPath {
              path: file_content.path.as_path().to_path_buf(),
            },
          })
          .collect();
        dbg!(&file_stats);
        HighLevelPathStats { stats: file_stats }
      });
      future::result(as_path_stats)
    })
    .to_boxed()
}

fn directories_expand_impl(
  digests: Vec<DirectoryDigest>,
) -> BoxFuture<HighLevelExpandDirectoriesMapping, DirectoryFFIError> {
  let expand_tasks: Vec<BoxFuture<HighLevelPathStats, _>> = digests
    .clone()
    .into_iter()
    .map(|digest| directories_expand_single(digest))
    .collect();
  let all_path_stats: BoxFuture<Vec<HighLevelPathStats>, _> =
    future::join_all(expand_tasks).to_boxed();

  all_path_stats
    .map(move |all_path_stats| {
      dbg!(&all_path_stats);
      HighLevelExpandDirectoriesMapping {
        mapping: digests
          .into_iter()
          .zip(all_path_stats.into_iter())
          .collect(),
      }
    })
    .to_boxed()
}

#[no_mangle]
pub unsafe extern "C" fn directories_expand(
  request: *const ExpandDirectoriesRequest,
  result: *mut ExpandDirectoriesResult,
) {
  let owned_request = HighLevelExpandDirectoriesRequest::from_ffi(*request);
  let HighLevelExpandDirectoriesRequest { requests } = owned_request;
  let expand_result: Result<HighLevelExpandDirectoriesMapping, _> =
    remexec::block_on_with_persistent_runtime(directories_expand_impl(requests));
  let owned_result = match expand_result {
    Ok(mapping) => HighLevelExpandDirectoriesResult::Successful(mapping),
    Err(e) => HighLevelExpandDirectoriesResult::Failed(CString::new(format!("{:?}", e)).unwrap()),
  };
  *result = owned_result.into_ffi();
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct UploadDirectoriesRequest {
  num_path_stats: SizeType,
  path_stats: *const PathStats,
}

pub struct HighLevelUploadDirectoriesRequest {
  pub path_stats: Vec<HighLevelPathStats>,
}
impl NativeWrapper<UploadDirectoriesRequest> for HighLevelUploadDirectoriesRequest {
  unsafe fn from_ffi(req: UploadDirectoriesRequest) -> Self {
    let UploadDirectoriesRequest {
      num_path_stats,
      path_stats,
    } = req;
    let path_stats: &[PathStats] = slice::from_raw_parts(path_stats, num_path_stats as usize);
    HighLevelUploadDirectoriesRequest {
      path_stats: path_stats
        .iter()
        .map(|path_stats| HighLevelPathStats::from_ffi(*path_stats))
        .collect(),
    }
  }
  fn into_ffi(self) -> UploadDirectoriesRequest {
    let HighLevelUploadDirectoriesRequest { path_stats } = self;
    let owned: Vec<PathStats> = path_stats
      .into_iter()
      .map(|path_stats| path_stats.into_ffi())
      .collect();
    let ret = UploadDirectoriesRequest {
      num_path_stats: owned.len() as SizeType,
      path_stats: owned.as_ptr(),
    };
    mem::forget(owned);
    ret
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
pub enum UploadDirectoriesResultStatus {
  UploadDirectoriesSucceeded,
  UploadDirectoriesFailed,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct UploadDirectoriesResult {
  pub mapping: ExpandDirectoriesMapping,
  pub error_message: *mut os::raw::c_char,
  pub status: UploadDirectoriesResultStatus,
}
impl Default for UploadDirectoriesResult {
  fn default() -> Self {
    UploadDirectoriesResult {
      mapping: ExpandDirectoriesMapping::default(),
      error_message: ptr::null_mut(),
      status: UploadDirectoriesResultStatus::UploadDirectoriesFailed,
    }
  }
}

#[derive(Debug, Clone)]
pub enum HighLevelUploadDirectoriesResult {
  Successful(HighLevelExpandDirectoriesMapping),
  Failed(CString),
}
impl NativeWrapper<UploadDirectoriesResult> for HighLevelUploadDirectoriesResult {
  unsafe fn from_ffi(result: UploadDirectoriesResult) -> Self {
    let UploadDirectoriesResult {
      mapping,
      error_message,
      status,
    } = result;
    match status {
      UploadDirectoriesResultStatus::UploadDirectoriesSucceeded => {
        HighLevelUploadDirectoriesResult::Successful(HighLevelExpandDirectoriesMapping::from_ffi(
          mapping,
        ))
      }
      UploadDirectoriesResultStatus::UploadDirectoriesFailed => {
        HighLevelUploadDirectoriesResult::Failed(CString::from_raw(error_message))
      }
    }
  }
  fn into_ffi(self) -> UploadDirectoriesResult {
    match self {
      Self::Successful(mapping) => UploadDirectoriesResult {
        mapping: mapping.into_ffi(),
        error_message: ptr::null_mut(),
        status: UploadDirectoriesResultStatus::UploadDirectoriesSucceeded,
      },
      Self::Failed(error_message) => UploadDirectoriesResult {
        mapping: ExpandDirectoriesMapping::default(),
        error_message: error_message.into_raw(),
        status: UploadDirectoriesResultStatus::UploadDirectoriesFailed,
      },
    }
  }
}

fn directories_upload_single(
  path_stats: HighLevelPathStats,
) -> BoxFuture<DirectoryDigest, DirectoryFFIError> {
  let HighLevelPathStats { stats } = path_stats;
  let mut trie = merkle_trie::MerkleTrie::<ShmKey>::new();
  let abstract_stats: Vec<merkle_trie::FileStat<ShmKey>> =
    stats.into_iter().map(|stat| stat.into()).collect();
  dbg!(&abstract_stats);
  future::result(
    trie
      .populate(abstract_stats)
      .map_err(|e| DirectoryFFIError::from(e)),
  )
  .and_then(|()| {
    remexec::MerkleTrieNode::recursively_upload_trie(trie)
      .map_err(|e| DirectoryFFIError::from(e))
      .to_boxed()
  })
  .to_boxed()
}

fn directories_upload_impl(
  expansions: Vec<HighLevelPathStats>,
) -> BoxFuture<HighLevelExpandDirectoriesMapping, DirectoryFFIError> {
  let upload_tasks: Vec<BoxFuture<DirectoryDigest, _>> = expansions
    .iter()
    .map(|file_stats| directories_upload_single(file_stats.clone()))
    .collect();
  let digests: BoxFuture<Vec<DirectoryDigest>, _> = future::join_all(upload_tasks).to_boxed();

  digests
    .map(move |digests| HighLevelExpandDirectoriesMapping {
      mapping: digests.into_iter().zip(expansions.into_iter()).collect(),
    })
    .to_boxed()
}

#[no_mangle]
pub unsafe extern "C" fn directories_upload(
  request: *const UploadDirectoriesRequest,
  result: *mut UploadDirectoriesResult,
) {
  let owned_request = HighLevelUploadDirectoriesRequest::from_ffi(*request);
  let HighLevelUploadDirectoriesRequest { path_stats } = owned_request;
  let upload_result: Result<HighLevelExpandDirectoriesMapping, _> =
    remexec::block_on_with_persistent_runtime(directories_upload_impl(path_stats));
  let owned_result = match upload_result {
    Ok(mapping) => HighLevelUploadDirectoriesResult::Successful(mapping),
    Err(e) => HighLevelUploadDirectoriesResult::Failed(CString::new(format!("{:?}", e)).unwrap()),
  };
  *result = owned_result.into_ffi();
}

enum TreeTraversalEntry {
  File(PathBuf, Digest),
  KnownDir(PathBuf, Digest),
  Directory(PathBuf),
}

struct GitTreeTraversalContext {
  map: IndexMap<PathBuf, Vec<TreeTraversalEntry>>,
}

fn to_bazel_digest(pants_digest: Digest) -> bazel_remexec::Digest {
  let mut digest = bazel_remexec::Digest::new();
  digest.set_hash(pants_digest.0.to_hex());
  digest.set_size_bytes(pants_digest.1 as i64);
  digest
}

impl GitTreeTraversalContext {
  fn new() -> Self {
    GitTreeTraversalContext {
      map: IndexMap::new(),
    }
  }

  fn add_file(&mut self, parent_directory: PathBuf, relpath: PathBuf, digest: Digest) {
    let entry = self.map.entry(parent_directory).or_insert_with(Vec::new);
    entry.push(TreeTraversalEntry::File(relpath, digest));
  }

  fn add_known_directory(&mut self, parent_directory: PathBuf, relpath: PathBuf, digest: Digest) {
    let entry = self.map.entry(parent_directory).or_insert_with(Vec::new);
    entry.push(TreeTraversalEntry::KnownDir(relpath, digest));
  }

  fn add_directory(&mut self, parent_directory: PathBuf, relpath: PathBuf) {
    let entry = self.map.entry(parent_directory).or_insert_with(Vec::new);
    entry.push(TreeTraversalEntry::Directory(relpath));
  }

  fn upload_recursive_directories(&mut self) -> BoxFuture<(), DirectoryFFIError> {
    /* FIXME: make this function work!!! */
    let mut result_map: IndexMap<PathBuf, Digest> = IndexMap::new();
    while let Some((path, entries)) = self.map.pop() {
      let mut file_nodes: Vec<bazel_remexec::FileNode> = vec![];
      let mut directory_nodes: Vec<bazel_remexec::DirectoryNode> = vec![];
      for entry in entries.into_iter() {
        match entry {
          TreeTraversalEntry::File(relpath, digest) => {
            let mut file_node = bazel_remexec::FileNode::new();
            file_node.set_name(format!("{}", relpath.display()));
            file_node.set_digest(to_bazel_digest(digest));
            file_nodes.push(file_node);
          }
          TreeTraversalEntry::KnownDir(relpath, digest) => {
            let mut directory_node = bazel_remexec::DirectoryNode::new();
            directory_node.set_name(format!("{}", relpath.display()));
            directory_node.set_digest(to_bazel_digest(digest));
            directory_nodes.push(directory_node);
          }
          TreeTraversalEntry::Directory(relpath) => {
            let full_path = path.join(relpath);
            result_map.get(full_path)
          },
        }
      }
    }
  }
}

#[repr(C)]
pub struct TreeTraversalFFIContext {
  pub inner_context: *mut os::raw::c_void,
}

impl TreeTraversalFFIContext {
  unsafe fn as_context(&mut self) -> &mut GitTreeTraversalContext {
    &mut *mem::transmute::<*mut os::raw::c_void, *mut GitTreeTraversalContext>(self.inner_context)
  }
}

#[no_mangle]
pub unsafe extern "C" fn tree_traversal_init_context(ctx: &mut TreeTraversalFFIContext) {
  ctx.inner_context = mem::transmute::<*mut GitTreeTraversalContext, *mut os::raw::c_void>(
    Box::into_raw(Box::new(GitTreeTraversalContext::new())),
  );
}

#[no_mangle]
pub unsafe extern "C" fn tree_traversal_destroy_context(ctx: &mut TreeTraversalFFIContext) {
  let git_context = ctx.as_context();
  block_on_with_persistent_runtime(git_context.upload_recursive_directories())
    .expect("uploading recursive directories should not fail!");
  Box::from_raw(ctx.inner_context);
}

#[no_mangle]
pub unsafe extern "C" fn tree_traversal_add_file(
  ctx: &mut TreeTraversalFFIContext,
  parent_directory: *const os::raw::c_char,
  relpath: *const os::raw::c_char,
  digest: &Digest,
) {
  let git_context = ctx.as_context();
  let parent_directory_name_slice = OsStr::from_bytes(CStr::from_ptr(parent_directory).to_bytes());
  let parent_directory = PathBuf::from(&parent_directory_name_slice);
  let relpath_name_slice = OsStr::from_bytes(CStr::from_ptr(relpath).to_bytes());
  let relpath = PathBuf::from(&relpath_name_slice);
  git_context.add_file(parent_directory, relpath, *digest);
}

#[no_mangle]
pub unsafe extern "C" fn tree_traversal_add_known_directory(
  ctx: &mut TreeTraversalFFIContext,
  parent_directory: *const os::raw::c_char,
  relpath: *const os::raw::c_char,
  digest: &Digest,
) {
  let git_context = ctx.as_context();
  let parent_directory_name_slice = OsStr::from_bytes(CStr::from_ptr(parent_directory).to_bytes());
  let parent_directory = PathBuf::from(&parent_directory_name_slice);
  let relpath_name_slice = OsStr::from_bytes(CStr::from_ptr(relpath).to_bytes());
  let relpath = PathBuf::from(&relpath_name_slice);
  git_context.add_known_directory(parent_directory, relpath, *digest);
}

#[no_mangle]
pub unsafe extern "C" fn tree_traversal_add_directory(
  ctx: &mut TreeTraversalFFIContext,
  parent_directory: *const os::raw::c_char,
  relpath: *const os::raw::c_char,
) {
  let git_context = ctx.as_context();
  let parent_directory_name_slice = OsStr::from_bytes(CStr::from_ptr(parent_directory).to_bytes());
  let parent_directory = PathBuf::from(&parent_directory_name_slice);
  let relpath_name_slice = OsStr::from_bytes(CStr::from_ptr(relpath).to_bytes());
  let relpath = PathBuf::from(&relpath_name_slice);
  git_context.add_directory(parent_directory, relpath);
}

#[repr(C)]
pub enum DirectoryOidCheckMappingResult {
  OidMappingExists,
  OidMappingDoesNotExist,
  OidMappingOtherError,
}

#[no_mangle]
pub unsafe extern "C" fn directory_oid_check_mapping(
  fingerprint: Fingerprint,
  result: *mut Digest,
) -> DirectoryOidCheckMappingResult {
  match block_on_with_persistent_runtime(GIT_OID_DB.load_bytes_with(fingerprint, |bytes| {
    str::from_utf8(bytes.as_ref())
      .map(|s| s.to_string())
      .map_err(|e| format!("{:?}", e))
  })) {
    Ok(Some(s)) => {
      let digest: Digest = serde_json::from_str(&s).expect("failed to unwrap json digest");
      *result = digest;
      DirectoryOidCheckMappingResult::OidMappingExists
    }
    Ok(None) => DirectoryOidCheckMappingResult::OidMappingDoesNotExist,
    Err(_) => DirectoryOidCheckMappingResult::OidMappingOtherError,
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::merkle_trie::{tests::*, PathComponents};

  use std::path::PathBuf;

  #[test]
  fn directory_upload_expand_end_to_end() -> Result<(), DirectoryFFIError> {
    let input: Vec<(PathComponents, &str)> = generate_example_input();

    let mmapped_input: Vec<(PathComponents, ShmKey)> = input
      .iter()
      .map(|(c, s)| {
        (
          c.clone(),
          remexec::memory_map_file_content(s.as_bytes()).unwrap().key,
        )
      })
      .collect();

    let ffi_mapped: Vec<(PathBuf, ShmKey)> = mmapped_input
      .into_iter()
      .map(|(c, key)| (c.into_path(), key))
      .collect();
    let ffi_input: Vec<HighLevelFileStat> = ffi_mapped
      .iter()
      .map(|(path, key)| {
        let rel_path = HighLevelChildRelPath { path: path.clone() };
        HighLevelFileStat {
          rel_path,
          key: *key,
        }
      })
      .collect();
    let input_path_stats = vec![HighLevelPathStats {
      stats: ffi_input.clone(),
    }];

    let upload_request = HighLevelUploadDirectoriesRequest {
      path_stats: input_path_stats,
    };
    let upload_request_low_level = upload_request.into_ffi();

    let mut upload_result_low_level = UploadDirectoriesResult::default();
    unsafe { directories_upload(&upload_request_low_level, &mut upload_result_low_level) }
    let upload_result =
      unsafe { HighLevelUploadDirectoriesResult::from_ffi(upload_result_low_level) };

    let uploaded_mapping = match upload_result {
      HighLevelUploadDirectoriesResult::Successful(mapping) => mapping,
      _ => unreachable!(),
    };
    let uploaded: Vec<(DirectoryDigest, HighLevelPathStats)> = uploaded_mapping
      .mapping
      .iter()
      .map(|(dig, stats)| (dig.clone(), stats.clone()))
      .collect();
    assert_eq!(1, uploaded.len());
    let (dir_digest, path_stats) = uploaded.get(0).unwrap();

    let ffi_output = path_stats.stats.clone();
    assert_eq!(ffi_input, ffi_output);

    /* Check that the expanded directory also has the same contents! */
    let expand_request = HighLevelExpandDirectoriesRequest {
      requests: vec![*dir_digest],
    };
    let expand_request_low_level = expand_request.into_ffi();

    let mut expand_result_low_level = ExpandDirectoriesResult::default();
    unsafe { directories_expand(&expand_request_low_level, &mut expand_result_low_level) }
    let expand_result =
      unsafe { HighLevelExpandDirectoriesResult::from_ffi(expand_result_low_level) };

    let expanded_mapping = match expand_result {
      HighLevelExpandDirectoriesResult::Successful(mapping) => mapping,
      _ => unreachable!(),
    };
    assert_eq!(uploaded_mapping, expanded_mapping);

    Ok(())
  }
}
