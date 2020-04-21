/* From https://gist.github.com/garcia556/8231e844a90457c99cc72e5add8388e4!! */
use super::mmap_bindings::{self, key_t, size_t, IPC_CREAT, IPC_R, IPC_RMID, IPC_W};

use hashing::{Digest, Fingerprint};

use lazy_static::lazy_static;
use parking_lot::RwLock;

use std::collections::HashMap;
use std::convert::{From, Into};
use std::default::Default;
use std::ffi::{CStr, CString};
use std::io;
use std::mem;
use std::ops::{Deref, DerefMut};
use std::os;
use std::ptr;
use std::slice;
use std::str;
use std::sync::Arc;

type SizeType = u64;

lazy_static! {
  static ref IN_PROCESS_SHM_MAPPINGS: Arc<RwLock<HashMap<ShmKey, ShmHandle>>> =
    Arc::new(RwLock::new(HashMap::new()));
}

#[derive(Debug)]
pub enum ShmError {
  MappingDidNotExist,
  DigestDidNotMatch(ShmKey),
  InternalError(String),
}

impl From<String> for ShmError {
  fn from(err: String) -> Self {
    ShmError::InternalError(err)
  }
}

#[repr(C)]
#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
pub struct ShmKey {
  pub size_bytes: SizeType,
  pub fingerprint: Fingerprint,
}
impl Default for ShmKey {
  fn default() -> Self {
    ShmKey::from(hashing::EMPTY_DIGEST)
  }
}

impl From<ShmKey> for key_t {
  fn from(value: ShmKey) -> Self {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut hasher = DefaultHasher::new();
    Hash::hash(&value, &mut hasher);

    hasher.finish() as key_t
  }
}

impl From<Digest> for ShmKey {
  fn from(digest: Digest) -> Self {
    let Digest(fingerprint, size_bytes) = digest;
    ShmKey {
      fingerprint,
      size_bytes: size_bytes as SizeType,
    }
  }
}

impl Into<Digest> for ShmKey {
  fn into(self: Self) -> Digest {
    let ShmKey {
      fingerprint,
      size_bytes,
    } = self;
    Digest(fingerprint, size_bytes as usize)
  }
}

#[derive(Debug, Copy, Clone)]
pub enum CreationBehavior {
  CreateNew(*const os::raw::c_void),
  DoNotCreateNew,
}

#[derive(Debug, Copy, Clone)]
pub struct ShmRequest {
  pub key: ShmKey,
  pub creation_behavior: CreationBehavior,
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmRetrieveRequest {
  pub key: ShmKey,
}

impl From<ShmRetrieveRequest> for ShmRequest {
  fn from(req: ShmRetrieveRequest) -> Self {
    let ShmRetrieveRequest { key } = req;
    ShmRequest {
      key,
      creation_behavior: CreationBehavior::DoNotCreateNew,
    }
  }
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum ShmRetrieveResultStatus {
  RetrieveSucceeded,
  RetrieveDidNotExist,
  RetrieveInternalError,
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmRetrieveResult {
  pub key: ShmKey,
  pub address: *const os::raw::c_void,
  pub error_message: *mut os::raw::c_char,
  pub status: ShmRetrieveResultStatus,
}
impl ShmRetrieveResult {
  pub fn successful(address: *const os::raw::c_void, key: ShmKey) -> Self {
    ShmRetrieveResult {
      key,
      status: ShmRetrieveResultStatus::RetrieveSucceeded,
      address,
      ..Default::default()
    }
  }
  pub fn did_not_exist(key: ShmKey) -> Self {
    ShmRetrieveResult {
      key,
      status: ShmRetrieveResultStatus::RetrieveDidNotExist,
      ..Default::default()
    }
  }
  pub fn errored(error_message: *mut os::raw::c_char, key: ShmKey) -> Self {
    ShmRetrieveResult {
      key,
      status: ShmRetrieveResultStatus::RetrieveInternalError,
      error_message,
      ..Default::default()
    }
  }
}
impl Default for ShmRetrieveResult {
  fn default() -> Self {
    ShmRetrieveResult {
      key: ShmKey::default(),
      status: ShmRetrieveResultStatus::RetrieveInternalError,
      address: ptr::null(),
      error_message: ptr::null_mut(),
    }
  }
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmAllocateRequest {
  pub key: ShmKey,
  pub source: *const os::raw::c_void,
}

impl From<ShmAllocateRequest> for ShmRequest {
  fn from(req: ShmAllocateRequest) -> Self {
    let ShmAllocateRequest { key, source } = req;
    ShmRequest {
      key,
      creation_behavior: CreationBehavior::CreateNew(source),
    }
  }
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum ShmAllocateResultStatus {
  AllocationSucceeded,
  DigestDidNotMatch,
  AllocationFailed,
}

/* FIXME: Using enums with entries inside of each case causes jnr-ffi to read structs as zeroed for
 * some reason. We're not too concerned about the size of these packets, so having fields for every
 * eventuality won't hurt too much. */
#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmAllocateResult {
  pub key: ShmKey,
  pub address: *const os::raw::c_void,
  pub error_message: *mut os::raw::c_char,
  pub status: ShmAllocateResultStatus,
}
impl ShmAllocateResult {
  pub fn successful(address: *const os::raw::c_void, key: ShmKey) -> Self {
    assert!(!address.is_null());
    ShmAllocateResult {
      status: ShmAllocateResultStatus::AllocationSucceeded,
      address,
      key,
      ..Default::default()
    }
  }
  pub fn failing(error_message: *mut os::raw::c_char, key: ShmKey) -> Self {
    assert!(!error_message.is_null());
    ShmAllocateResult {
      key,
      status: ShmAllocateResultStatus::AllocationFailed,
      error_message,
      ..Default::default()
    }
  }
  pub fn mismatched_digest(key: ShmKey) -> Self {
    ShmAllocateResult {
      status: ShmAllocateResultStatus::DigestDidNotMatch,
      key,
      ..Default::default()
    }
  }
}
impl Default for ShmAllocateResult {
  fn default() -> Self {
    ShmAllocateResult {
      status: ShmAllocateResultStatus::AllocationFailed,
      key: ShmKey::default(),
      address: ptr::null(),
      error_message: ptr::null_mut(),
    }
  }
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmDeleteRequest {
  pub key: ShmKey,
}

impl From<ShmDeleteRequest> for ShmRequest {
  fn from(req: ShmDeleteRequest) -> Self {
    let ShmDeleteRequest { key } = req;
    ShmRequest {
      key,
      creation_behavior: CreationBehavior::DoNotCreateNew,
    }
  }
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum ShmDeleteResultStatus {
  DeletionSucceeded,
  DeleteDidNotExist,
  DeleteInternalError,
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub struct ShmDeleteResult {
  pub key: ShmKey,
  pub error_message: *mut os::raw::c_char,
  pub status: ShmDeleteResultStatus,
}
impl ShmDeleteResult {
  pub fn successful(key: ShmKey) -> Self {
    ShmDeleteResult {
      key,
      status: ShmDeleteResultStatus::DeletionSucceeded,
      ..Default::default()
    }
  }
  pub fn did_not_exist(key: ShmKey) -> Self {
    ShmDeleteResult {
      key,
      status: ShmDeleteResultStatus::DeleteDidNotExist,
      ..Default::default()
    }
  }
  pub fn internal_error(error_message: *mut os::raw::c_char, key: ShmKey) -> Self {
    assert!(!error_message.is_null());
    ShmDeleteResult {
      key,
      status: ShmDeleteResultStatus::DeleteInternalError,
      error_message,
    }
  }
}
impl Default for ShmDeleteResult {
  fn default() -> Self {
    ShmDeleteResult {
      key: ShmKey::default(),
      status: ShmDeleteResultStatus::DeleteInternalError,
      error_message: ptr::null_mut(),
    }
  }
}

#[derive(Debug, Copy, Clone)]
pub struct ShmHandle {
  key: ShmKey,
  size_bytes: usize,
  mmap_addr: *mut os::raw::c_void,
  shared_memory_identifier: os::raw::c_int,
}

unsafe impl Send for ShmHandle {}
unsafe impl Sync for ShmHandle {}

impl ShmHandle {
  pub fn get_key(&self) -> ShmKey {
    self.key
  }

  pub fn get_base_address(&self) -> *const os::raw::c_void {
    self.mmap_addr
  }
}

impl Deref for ShmHandle {
  type Target = [u8];

  fn deref(&self) -> &[u8] {
    unsafe {
      slice::from_raw_parts(
        mem::transmute::<*mut os::raw::c_void, *const u8>(self.mmap_addr),
        self.size_bytes as usize,
      )
    }
  }
}

impl DerefMut for ShmHandle {
  fn deref_mut(&mut self) -> &mut [u8] {
    unsafe {
      slice::from_raw_parts_mut(
        mem::transmute::<*mut os::raw::c_void, *mut u8>(self.mmap_addr),
        self.size_bytes as usize,
      )
    }
  }
}

impl ShmHandle {
  fn validate_digest(&self, key: ShmKey, source: *const os::raw::c_void) -> Result<(), ShmError> {
    let source_slice: &[u8] = unsafe {
      slice::from_raw_parts(
        mem::transmute::<*const os::raw::c_void, *const u8>(source),
        key.size_bytes as usize,
      )
    };
    let calculated_digest: ShmKey = hashing::Digest::of_bytes(source_slice).into();
    if calculated_digest != key {
      Err(ShmError::DigestDidNotMatch(calculated_digest))
    } else {
      Ok(())
    }
  }

  fn validate_digest_and_write_bytes_to_destination(
    &mut self,
    key: ShmKey,
    source: *const os::raw::c_void,
  ) -> Result<(), ShmError> {
    self.validate_digest(key, source)?;

    let source_slice: &[u8] = unsafe {
      slice::from_raw_parts(
        mem::transmute::<*const os::raw::c_void, *const u8>(source),
        key.size_bytes as usize,
      )
    };
    let destination: &mut [u8] = &mut *self;
    destination.copy_from_slice(source_slice);
    Ok(())
  }

  pub fn new(request: ShmRequest) -> Result<Self, ShmError> {
    let ShmRequest {
      key,
      creation_behavior,
    } = request;

    std::fs::write(
      "/Users/dmcclanahan/projects/active/upc/key.txt",
      &format!("+{:?}", key),
    )
    .unwrap();

    let maybe_existing_handle: Option<ShmHandle> = {
      let mappings = (*IN_PROCESS_SHM_MAPPINGS).read();
      mappings.get(&key).cloned()
    };
    if let Some(existing_handle) = maybe_existing_handle {
      match creation_behavior {
        CreationBehavior::CreateNew(source) => {
          /* We validate the digest here only because we know the segment was already created and
           * should have exactly the right bytes! */
          existing_handle.validate_digest(key, source)?;
          Ok(existing_handle)
        }
        CreationBehavior::DoNotCreateNew => Ok(existing_handle),
      }
    } else {
      std::fs::write(
        "/Users/dmcclanahan/projects/active/upc/key.txt",
        &format!("-{:?}", key),
      )
      .unwrap();

      let fd_perm = IPC_R
        | IPC_W
        | match creation_behavior {
          CreationBehavior::CreateNew(_) => IPC_CREAT,
          CreationBehavior::DoNotCreateNew => 0,
        };
      let shm_address_key: key_t = key.into();

      std::fs::write(
        "/Users/dmcclanahan/projects/active/upc/addr_key.txt",
        &format!("{:?}", shm_address_key),
      )
      .unwrap();

      let shm_fd = unsafe {
        let fd = mmap_bindings::shmget(
          shm_address_key,
          key.size_bytes as size_t,
          fd_perm as os::raw::c_int,
        );
        if fd == -1 {
          let errno = io::Error::last_os_error();
          match (creation_behavior, errno.kind()) {
            (CreationBehavior::DoNotCreateNew, io::ErrorKind::NotFound) => {
              return Err(ShmError::MappingDidNotExist);
            }
            _ => {
              let message = format!(
                "failed to open SHM with creation behavior {:?}: {:?}",
                creation_behavior, errno,
              );
              std::fs::write(
                "/Users/dmcclanahan/projects/active/upc/message.txt",
                &format!("{:?}", &message),
              )
              .unwrap();
              return Err(message.into());
            }
          }
        }
        fd
      };

      std::fs::write(
        "/Users/dmcclanahan/projects/active/upc/shm_fd.txt",
        &format!("{:?}", shm_fd),
      )
      .unwrap();

      let shmat_prot = 0;
      let mmap_addr = unsafe {
        let addr = mmap_bindings::shmat(shm_fd, ptr::null(), shmat_prot as os::raw::c_int);
        if addr == MAP_FAILED() {
          let err = io::Error::last_os_error();
          return Err(format!("failed to mmap SHM at fd {:?}: {:?}", shm_fd, err).into());
        }
        addr
      };

      let mut result = ShmHandle {
        key,
        size_bytes: key.size_bytes as usize,
        mmap_addr,
        shared_memory_identifier: shm_fd,
      };

      std::fs::write(
        "/Users/dmcclanahan/projects/active/upc/result.txt",
        &format!("{:?}", &result),
      )
      .unwrap();

      match creation_behavior {
        CreationBehavior::CreateNew(source) => {
          /* Ensure we actually write the content to the shared memory region, when we allocate
           * it. */
          result.validate_digest_and_write_bytes_to_destination(key, source)?;
        }
        CreationBehavior::DoNotCreateNew => (),
      }

      (*IN_PROCESS_SHM_MAPPINGS).write().insert(key, result);
      Ok(result)
    }
  }

  /* Note: this destroys the mapping for every other process too! This should only be used to free
   * up memory, but even then, it's likely too many shared mappings will just end up getting paged
   * to disk. */
  /* TODO: investigate garbage collection policy for anonymous shared mappings, if necessary! */
  pub fn destroy_mapping(&mut self) -> Result<(), ShmError> {
    match (*IN_PROCESS_SHM_MAPPINGS).write().remove(&self.key) {
      Some(_) => (),
      None => return Err(format!("could not locate shm mapping to delete: {:?}", self.key).into()),
    }
    let mut rc = unsafe { mmap_bindings::shmdt(self.mmap_addr) };
    /* If the shmdt() call *didn't* fail, move on to shmctl() to destroy it for all processes. */
    if rc == 0 {
      rc = unsafe {
        mmap_bindings::shmctl(
          self.shared_memory_identifier,
          IPC_RMID as os::raw::c_int,
          ptr::null_mut(),
        )
      };
    }
    if rc == -1 {
      Err(
        format!(
          "error dropping shm mapping: {:?}",
          io::Error::last_os_error()
        )
        .into(),
      )
    } else {
      Ok(())
    }
  }
}

#[derive(Debug)]
pub struct CCharErrorMessage {
  message: String,
}
impl CCharErrorMessage {
  pub fn new(message: String) -> Self {
    CCharErrorMessage { message }
  }
  pub unsafe fn leak_null_terminated_c_string(self) -> *mut os::raw::c_char {
    let mut null_terminated_error_message: Vec<u8> = self
      .message
      .as_bytes()
      .iter()
      .chain(&['\0' as u8])
      .cloned()
      .collect();
    let buf: *mut u8 = null_terminated_error_message.as_mut_ptr();
    mem::forget(null_terminated_error_message);
    mem::transmute::<*mut u8, *mut os::raw::c_char>(buf)
  }
  pub unsafe fn from_c_str(c_str: *const os::raw::c_char) -> Result<Self, str::Utf8Error> {
    let c_str = CStr::from_ptr(c_str);
    str::from_utf8(c_str.to_bytes()).map(|s| CCharErrorMessage {
      message: s.to_string(),
    })
  }
}

/* This is a preprocessor define, so we have to recreate it. It could be a global variable, but
 * usage of mem::transmute::<>() isn't allowed at top level without a lazy_static!{}. */
#[allow(non_snake_case)]
pub unsafe fn MAP_FAILED() -> *mut os::raw::c_void {
  mem::transmute::<i64, *mut os::raw::c_void>(-1)
}

#[repr(C)]
pub struct ShmGetKeyRequest {
  pub size: SizeType,
  pub source: *const os::raw::c_void,
}
impl ShmGetKeyRequest {
  pub unsafe fn as_slice(&self) -> &[u8] {
    slice::from_raw_parts(
      mem::transmute::<*const os::raw::c_void, *const u8>(self.source),
      self.size as usize,
    )
  }
}

#[no_mangle]
pub unsafe extern "C" fn shm_get_key(request: *const ShmGetKeyRequest, result: *mut ShmKey) {
  let input_bytes = (*request).as_slice();
  let digest = Digest::of_bytes(input_bytes);
  let key: ShmKey = digest.into();
  *result = key
}

#[no_mangle]
pub unsafe extern "C" fn shm_retrieve(
  request: *const ShmRetrieveRequest,
  result: *mut ShmRetrieveResult,
) {
  let key = (*request).key;
  *result = match ShmHandle::new((*request).into()) {
    Ok(shm_handle) => ShmRetrieveResult::successful(shm_handle.get_base_address(), key),
    Err(ShmError::MappingDidNotExist) => ShmRetrieveResult::did_not_exist(key),
    Err(e) => {
      let error_message = CString::new(format!("{:?}", e)).unwrap();
      ShmRetrieveResult::errored(error_message.into_raw(), key)
    }
  };
}

#[no_mangle]
pub unsafe extern "C" fn shm_allocate(
  request: *const ShmAllocateRequest,
  result: *mut ShmAllocateResult,
) {
  let key = (*request).key;

  std::fs::write(
    "/Users/dmcclanahan/projects/active/upc/request.txt",
    &format!("+{:?}", request),
  )
  .unwrap();
  let shm_request = (*request).into();
  std::fs::write(
    "/Users/dmcclanahan/projects/active/upc/request.txt",
    &format!(">{:?}", shm_request),
  )
  .unwrap();

  *result = match ShmHandle::new(shm_request) {
    Ok(shm_handle) => ShmAllocateResult::successful(shm_handle.get_base_address(), key),
    Err(ShmError::DigestDidNotMatch(shm_key)) => ShmAllocateResult::mismatched_digest(shm_key),
    Err(e) => {
      let error: String = format!("{:?}", e);
      std::fs::write(
        "/Users/dmcclanahan/projects/active/upc/error.txt",
        &format!("!!{:?}", &error),
      )
      .unwrap();
      let error_message = CString::new(error).unwrap();
      ShmAllocateResult::failing(error_message.into_raw(), key)
    }
  };
}

#[no_mangle]
pub unsafe extern "C" fn shm_delete(
  request: *const ShmDeleteRequest,
  result: *mut ShmDeleteResult,
) {
  let key = (*request).key;
  *result = match ShmHandle::new((*request).into()).and_then(|mut handle| handle.destroy_mapping())
  {
    Ok(()) => ShmDeleteResult::successful(key),
    Err(ShmError::MappingDidNotExist) => ShmDeleteResult::did_not_exist(key),
    Err(e) => {
      let error_message = CString::new(format!("{:?}", e)).unwrap();
      ShmDeleteResult::internal_error(error_message.into_raw(), key)
    }
  };
}

#[no_mangle]
pub unsafe extern "C" fn shm_free_error_message(error_message: *mut os::raw::c_char) {
  CString::from_raw(error_message);
}

#[cfg(test)]
mod tests {
  use super::*;

  use hashing::{Digest, Fingerprint};

  use uuid::Uuid;

  use std::mem;
  use std::os;
  use std::slice;

  fn bytes_to_address(bytes: &[u8]) -> *const os::raw::c_void {
    unsafe { mem::transmute::<*const u8, *const os::raw::c_void>(bytes.as_ptr()) }
  }

  fn read_bytes_from_address(address: *const os::raw::c_void, len: usize) -> &'static [u8] {
    unsafe {
      slice::from_raw_parts(
        mem::transmute::<*const os::raw::c_void, *const u8>(address),
        len,
      )
    }
  }

  #[test]
  fn shm_get_key_ffi() {
    let known_source = "asdf".as_bytes();
    let get_key_request = ShmGetKeyRequest {
      size: known_source.len() as SizeType,
      source: unsafe { mem::transmute::<*const u8, *const os::raw::c_void>(known_source.as_ptr()) },
    };
    let mut key = ShmKey::default();
    unsafe { shm_get_key(&get_key_request, &mut key) };
    assert_eq!(
      key,
      ShmKey {
        fingerprint: Fingerprint::from_hex_string(
          "f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b"
        )
        .unwrap(),
        size_bytes: 4,
      }
    );
  }

  #[test]
  fn shm_allocate_bad_digest() {
    let random_source = Uuid::new_v4();
    let source_bytes: &[u8] = random_source.as_bytes();

    let bad_source = Uuid::new_v4();
    let bad_bytes: &[u8] = bad_source.as_bytes();

    assert_ne!(random_source, bad_source);
    assert_ne!(source_bytes, bad_bytes);

    let good_digest = Digest::of_bytes(source_bytes);
    let good_key: ShmKey = good_digest.into();

    let bad_digest = Digest::of_bytes(bad_bytes);
    let bad_key: ShmKey = bad_digest.into();

    assert_ne!(good_digest, bad_digest);
    assert_ne!(good_key, bad_key);

    let bad_allocate_request = ShmAllocateRequest {
      key: bad_key,
      source: unsafe { mem::transmute::<*const u8, *const os::raw::c_void>(source_bytes.as_ptr()) },
    };

    let mut result = ShmAllocateResult::default();
    unsafe { shm_allocate(&bad_allocate_request, &mut result) }

    let ShmAllocateResult { status, key, .. } = result;
    match (status, key) {
      (ShmAllocateResultStatus::DigestDidNotMatch, key) => {
        assert_eq!(key, good_key);
      }
      _ => unreachable!(),
    }
  }

  #[test]
  fn shm_allocate_retrieve_delete_end_to_end() {
    let random_source = Uuid::new_v4();
    let source_bytes: &[u8] = random_source.as_bytes();

    let digest = Digest::of_bytes(source_bytes);
    let key: ShmKey = digest.into();

    let retrieve_request = ShmRetrieveRequest { key };
    let mut retrieve_result = ShmRetrieveResult::default();

    unsafe { shm_retrieve(&retrieve_request, &mut retrieve_result) }
    assert_eq!(
      retrieve_result.status,
      ShmRetrieveResultStatus::RetrieveDidNotExist
    );

    let source_ptr = bytes_to_address(source_bytes);
    let allocate_request = ShmAllocateRequest {
      key,
      source: source_ptr,
    };

    let mut allocate_result = ShmAllocateResult::default();
    unsafe { shm_allocate(&allocate_request, &mut allocate_result) }
    let ShmAllocateResult {
      status, address, ..
    } = allocate_result;
    let shared_memory_address = match (status, address) {
      (ShmAllocateResultStatus::AllocationSucceeded, x) => x,
      x => unreachable!("did not expect allocation result {:?}", x),
    };
    /* Assert that we have been given a new address, pointing to the shared memory segment. */
    assert_ne!(shared_memory_address, source_ptr);
    /* Assert that the shared memory segment contains the same data we had in the original bytes! */
    assert_eq!(
      source_bytes,
      read_bytes_from_address(shared_memory_address, source_bytes.len())
    );

    let mut retrieve_result = ShmRetrieveResult::default();
    unsafe { shm_retrieve(&retrieve_request, &mut retrieve_result) }
    let ShmRetrieveResult {
      status, address, ..
    } = retrieve_result;
    let shared_memory_address_from_retrieve = match (status, address) {
      (ShmRetrieveResultStatus::RetrieveSucceeded, x) => x,
      x => unreachable!("did not expect retrieval result {:?}", x),
    };
    /* Assert that the address we retrieve is the same one we allocated. */
    assert_eq!(shared_memory_address, shared_memory_address_from_retrieve);

    /* Delete the allocation. */
    let delete_request = ShmDeleteRequest { key };
    let mut delete_result = ShmDeleteResult::default();
    unsafe { shm_delete(&delete_request, &mut delete_result) }
    assert_eq!(
      delete_result.status,
      ShmDeleteResultStatus::DeletionSucceeded,
    );
    /* Assert that it cannot be deleted again. */
    unsafe { shm_delete(&delete_request, &mut delete_result) }
    assert_eq!(
      delete_result.status,
      ShmDeleteResultStatus::DeleteDidNotExist,
    );

    /* Assert that the mapping no longer exists when retrieved. */
    let mut retrieve_result = ShmRetrieveResult::default();
    unsafe { shm_retrieve(&retrieve_request, &mut retrieve_result) }
    assert_eq!(
      retrieve_result.status,
      ShmRetrieveResultStatus::RetrieveDidNotExist,
    );
  }
}
