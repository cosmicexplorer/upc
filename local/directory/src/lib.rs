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

pub mod merkle_trie;
pub mod remexec;

use memory::shm::*;

use boxfuture::{BoxFuture, Boxable};
use fs::FileContent;
use hashing::{Digest, Fingerprint};

use futures01::{future, Future};
use protobuf;

use std::convert::{From, Into};
use std::ffi::OsStr;
use std::mem;
use std::os::{self, unix::ffi::OsStrExt};
use std::path::Path;
use std::slice;

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
  pub unsafe fn from_path(path: &Path) -> Self {
    let slice: &[u8] = path.as_os_str().as_bytes();
    let relpath: *const os::raw::c_char =
      mem::transmute::<*const u8, *const os::raw::c_char>(slice.as_ptr());
    ChildRelPath {
      relpath,
      relpath_size: slice.len() as u64,
    }
  }
}

/* NB: remove all directories from path stats!!! all path stats *strictly* just contain file paths!!
 * directory paths are *INFERRED*!!!! */
#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct FileStat {
  pub rel_path: ChildRelPath,
  pub key: ShmKey,
}
impl Into<merkle_trie::FileStat<ShmKey>> for FileStat {
  fn into(self: Self) -> merkle_trie::FileStat<ShmKey> {
    let FileStat { rel_path, key } = self;
    let components = merkle_trie::PathComponents::from_path(unsafe { rel_path.as_path() }).unwrap();
    merkle_trie::FileStat {
      components,
      terminal: merkle_trie::MerkleTrieTerminalEntry::File(key),
    }
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct PathStats {
  stats: *const FileStat,
  num_stats: u64,
}
impl PathStats {
  pub fn from_slice(stats: &[FileStat]) -> Self {
    let num_stats = stats.len();
    PathStats {
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
  ) -> Result<Self, DirectoryFFIError> {
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
      Ok(ExpandDirectoriesMapping {
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

fn memory_map_file_content(bytes: &[u8]) -> Result<ShmKey, DirectoryFFIError> {
  let digest = Digest::of_bytes(bytes);
  let key: ShmKey = digest.into();
  let source: *const os::raw::c_void =
    unsafe { mem::transmute::<*const u8, *const os::raw::c_void>(bytes.as_ptr()) };
  let request = ShmAllocateRequest { key, source };
  ShmHandle::new(request.into())
    .map(|handle| handle.get_key())
    .map_err(|e| format!("{:?}", e).into())
}

fn directories_expand_single(digest: DirectoryDigest) -> BoxFuture<PathStats, DirectoryFFIError> {
  let pants_digest: Digest = digest.into();
  let all_files_content: BoxFuture<Vec<FileContent>, String> =
    remexec::expand_directory(pants_digest);

  all_files_content
    .map_err(|e| format!("{:?}", e).into())
    .and_then(|all_files_content| {
      let file_uploads: Result<Vec<ShmKey>, _> = all_files_content
        .iter()
        .map(|file_content| memory_map_file_content(file_content.content.as_ref()))
        .collect();
      let as_path_stats: Result<PathStats, _> = file_uploads.map(|all_keys| {
        let file_stats: Vec<FileStat> = all_files_content
          .into_iter()
          .zip(all_keys)
          .map(|(file_content, key)| FileStat {
            rel_path: unsafe { ChildRelPath::from_path(&file_content.path) },
            key,
          })
          .collect();
        PathStats::from_slice(&file_stats)
      });
      future::result(as_path_stats)
    })
    .to_boxed()
}

fn directories_expand_impl(
  digests: &[DirectoryDigest],
) -> BoxFuture<ExpandDirectoriesMapping, DirectoryFFIError> {
  let digests: Vec<DirectoryDigest> = digests.to_vec();

  let expand_tasks: Vec<BoxFuture<PathStats, _>> = digests
    .clone()
    .into_iter()
    .map(|digest| directories_expand_single(digest))
    .collect();
  let all_path_stats: BoxFuture<Vec<PathStats>, _> = future::join_all(expand_tasks).to_boxed();

  all_path_stats
    .and_then(move |all_path_stats| {
      future::result(ExpandDirectoriesMapping::from_slices(
        &digests,
        &all_path_stats,
      ))
      .to_boxed()
    })
    .to_boxed()
}

#[no_mangle]
pub unsafe extern "C" fn directories_expand(
  request: ExpandDirectoriesRequest,
) -> ExpandDirectoriesResult {
  let digests: &[DirectoryDigest] = request.as_slice();
  let result: Result<ExpandDirectoriesMapping, _> =
    remexec::block_on_with_persistent_runtime(directories_expand_impl(digests));
  match result {
    Ok(mapping) => ExpandDirectoriesResult::ExpandDirectoriesSucceeded(mapping),
    Err(e) => {
      let error_message = CCharErrorMessage::new(format!("{:?}", e));
      ExpandDirectoriesResult::ExpandDirectoriesFailed(
        error_message.leak_null_terminated_c_string(),
      )
    }
  }
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
  let mut trie = merkle_trie::MerkleTrie::<ShmKey>::new();
  let abstract_stats: Vec<merkle_trie::FileStat<ShmKey>> =
    file_stats.iter().cloned().map(|stat| stat.into()).collect();
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
  all_path_stats: &[&[FileStat]],
) -> BoxFuture<ExpandDirectoriesMapping, DirectoryFFIError> {
  let expansions: Vec<PathStats> = all_path_stats
    .iter()
    .map(|stats| PathStats::from_slice(stats))
    .collect();

  let upload_tasks: Vec<BoxFuture<DirectoryDigest, _>> = all_path_stats
    .iter()
    .map(|file_stats| directories_upload_single(file_stats))
    .collect();
  let digests: BoxFuture<Vec<DirectoryDigest>, _> = future::join_all(upload_tasks).to_boxed();

  digests
    .and_then(move |digests| {
      future::result(ExpandDirectoriesMapping::from_slices(&digests, &expansions)).to_boxed()
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
    remexec::block_on_with_persistent_runtime(directories_upload_impl(&all_path_stats));
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
  #[allow(warnings)]
  #[test]
  fn directory_upload_expand_end_to_end() {
    let test_dir: Vec<(&str, &str)> = vec![("a.txt", "this is a.txt"), ("", "")];

    assert_eq!(2 + 2, 5);
  }
}
