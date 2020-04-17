/* From https://gist.github.com/garcia556/8231e844a90457c99cc72e5add8388e4!! */
use super::mmap_bindings::{self, key_t, size_t, IPC_CREAT, IPC_R, IPC_W};

use hashing;

use std::convert::From;
use std::io;
use std::mem;
use std::ops::{Deref, DerefMut};
use std::os;
use std::ptr;
use std::slice;

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
  pub fingerprint: hashing::Fingerprint,
  pub size_bytes: u64,
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

impl From<hashing::Digest> for ShmKey {
  fn from(digest: hashing::Digest) -> Self {
    let hashing::Digest(fingerprint, size_bytes) = digest;
    ShmKey {
      fingerprint,
      size_bytes: size_bytes as u64,
    }
  }
}

#[derive(Debug, Copy, Clone)]
pub enum CreationBehavior {
  CreateNew(*const os::raw::c_void),
  DoNotCreateNew,
}

pub struct ShmRequest {
  pub key: ShmKey,
  pub creation_behavior: CreationBehavior,
}

#[repr(C)]
#[derive(Copy, Clone)]
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
pub enum ShmRetrieveResult {
  RetrieveSucceeded(*mut os::raw::c_void),
  RetrieveDidNotExist,
  RetrieveInternalError(*mut os::raw::c_char),
}

#[repr(C)]
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
pub enum ShmAllocateResult {
  AllocationSucceeded(*mut os::raw::c_void),
  DigestDidNotMatch(ShmKey),
  AllocationFailed(*mut os::raw::c_char),
}

pub struct ShmHandle {
  size_bytes: usize,
  mmap_addr: *mut os::raw::c_void,
}

impl ShmHandle {
  pub unsafe fn get_base_address(&mut self) -> *mut os::raw::c_void {
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
  fn validate_digest_and_write_bytes_to_destination(
    &mut self,
    key: ShmKey,
    source: *const os::raw::c_void,
  ) -> Result<(), ShmError> {
    let source: &[u8] = unsafe {
      slice::from_raw_parts(
        mem::transmute::<*const os::raw::c_void, *const u8>(source),
        key.size_bytes as usize,
      )
    };
    let calculated_digest: ShmKey = hashing::Digest::of_bytes(source).into();
    if calculated_digest != key {
      return Err(ShmError::DigestDidNotMatch(calculated_digest));
    }

    let destination: &mut [u8] = &mut *self;
    destination.copy_from_slice(source);
    Ok(())
  }

  pub fn new(request: ShmRequest) -> Result<Self, ShmError> {
    let ShmRequest {
      key,
      creation_behavior,
    } = request;

    let fd_perm = IPC_R
      | IPC_W
      | match creation_behavior {
        CreationBehavior::CreateNew(_) => IPC_CREAT,
        CreationBehavior::DoNotCreateNew => 0,
      };
    let shm_address_key: key_t = key.into();

    let shm_fd = unsafe {
      /* TODO: I think we don't need to do anything to close this when we're done?? */
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
            return Err(
              format!(
                "failed to open SHM with creation behavior {:?}: {:?}",
                creation_behavior, errno,
              )
              .into(),
            );
          }
        }
      }
      fd
    };

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
      size_bytes: key.size_bytes as usize,
      mmap_addr,
    };

    /* TODO: Whenever we call allocate_shm(), we validate the digest and rewrite the contents,
     * regardless of whether the source has changed. This *should* be the kind of idempotency that
     * makes parallelism "just work". */
    match creation_behavior {
      CreationBehavior::CreateNew(source) => {
        result.validate_digest_and_write_bytes_to_destination(key, source)?;
        Ok(result)
      }
      CreationBehavior::DoNotCreateNew => Ok(result),
    }
  }

  /* Note: this destroys the mapping for every other process too! This should only be used to free
   * up memory, but even then, it's likely too many shared mappings will just end up getting paged
   * to disk. */
  /* TODO: investigate garbage collection policy for anonymous shared mappings, if necessary! */
  #[allow(dead_code)]
  pub fn destroy_mapping(&mut self) -> Result<(), ShmError> {
    let rc = unsafe { mmap_bindings::shmdt(self.mmap_addr) };
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

pub struct CCharErrorMessage {
  message: String,
}

impl CCharErrorMessage {
  pub fn new(message: String) -> Self {
    CCharErrorMessage { message }
  }
  pub fn leak_null_terminated_c_string(self) -> *mut os::raw::c_char {
    let null_terminated_error_message: Vec<u8> = self
      .message
      .as_bytes()
      .iter()
      .chain(&['\0' as u8])
      .cloned()
      .collect();
    let boxed_bytes: Box<[u8]> = null_terminated_error_message.into();
    Box::into_raw(boxed_bytes) as *mut os::raw::c_char
  }
}

/* This is a preprocessor define, so we have to recreate it. It could be a global variable, but
 * usage of mem::transmute::<>() isn't allowed at top level without a lazy_static!{}. */
#[allow(non_snake_case)]
pub unsafe fn MAP_FAILED() -> *mut os::raw::c_void {
  mem::transmute::<i64, *mut os::raw::c_void>(-1)
}

#[no_mangle]
pub unsafe extern "C" fn retrieve_shm(request: ShmRetrieveRequest) -> ShmRetrieveResult {
  match ShmHandle::new(request.into()) {
    Ok(mut shm_handle) => ShmRetrieveResult::RetrieveSucceeded(shm_handle.get_base_address()),
    Err(ShmError::MappingDidNotExist) => ShmRetrieveResult::RetrieveDidNotExist,
    Err(e) => {
      let error_message = CCharErrorMessage::new(format!("{:?}", e));
      ShmRetrieveResult::RetrieveInternalError(error_message.leak_null_terminated_c_string())
    }
  }
}

#[no_mangle]
pub unsafe extern "C" fn allocate_shm(request: ShmAllocateRequest) -> ShmAllocateResult {
  match ShmHandle::new(request.into()) {
    Ok(mut shm_handle) => ShmAllocateResult::AllocationSucceeded(shm_handle.get_base_address()),
    Err(ShmError::DigestDidNotMatch(shm_key)) => ShmAllocateResult::DigestDidNotMatch(shm_key),
    Err(e) => {
      let error_message = CCharErrorMessage::new(format!("{:?}", e));
      ShmAllocateResult::AllocationFailed(error_message.leak_null_terminated_c_string())
    }
  }
}
