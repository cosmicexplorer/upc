package upc.local.memory;

import jnr.ffi.*;
import jnr.ffi.types.*;

// import upc.local.memory.jnr.Fingerprint;


public class LibMemory {
  public static LibMemoryIface libMemoryInstance = null;
  public static jnr.ffi.Runtime runtime = null;

  public static LibMemoryIface setupLibrary(String[] paths) {
    LibraryLoader<LibMemoryIface> loader = LibraryLoader.create(LibMemoryIface.class);
    for (String path: paths) {
      loader.search(path);
    }
    libMemoryInstance = loader.load("memory");
    runtime = jnr.ffi.Runtime.getRuntime(libMemoryInstance);
    return libMemoryInstance;
  }

  public static class Error extends Exception {
    public Error(String message) {
      super(message);
    }
    public Error(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static interface LibMemoryIface {
    public ShmKey shm_get_key(ShmGetKeyRequest request);
    public ShmAllocateResult shm_allocate(ShmAllocateRequest request);
    public ShmRetrieveResult shm_retrieve(ShmRetrieveRequest request);
    public ShmDeleteResult shm_delete(ShmDeleteRequest request);
  }

  // common types!
  public static class Fingerprint extends upc.local.memory.jnr.Fingerprint {
    public Fingerprint(byte[] bytes) {
      super(runtime);
      setBytes(bytes);
    }
  }

  public static class ShmKey extends upc.local.memory.jnr.ShmKey {
    public ShmKey(Fingerprint fingerprintArg, long lengthArg) {
      super(runtime);
      setFingerprint(fingerprintArg);
      setLength(lengthArg);
    }

    public ShmKey(ShmKey otherShmKey) {
      super(runtime);
      copyFrom(otherShmKey);
    }
  }

  // shm_get_key() types!
  public static class ShmGetKeyRequest extends Struct {
    public final Pointer source = new Pointer();

    public ShmGetKeyRequest() {
      super(runtime);
    }

    public ShmGetKeyRequest(jnr.ffi.Runtime runtime) {
      super(runtime);
    }

    public ShmGetKeyRequest(jnr.ffi.Pointer sourceArg) throws ShmKeyError {
      super(runtime);
      source.set(sourceArg);
    }

    // @Override
    // public java.lang.String toString() {
    //   return "ShmGetKeyRequest { " + "source = " + source.size() + " }";
    // }
  }

  // shm_allocate() types!
  public static class ShmAllocateRequest extends Struct {
    public final ShmKey key = new ShmKey();
    public final Pointer source = new Pointer();

    public ShmAllocateRequest() {
      super(runtime);
    }

    public ShmAllocateRequest(jnr.ffi.Runtime runtime) {
      super(runtime);
    }

    public ShmAllocateRequest(ShmKey keyArg, jnr.ffi.Pointer sourceArg) throws ShmKeyError {
      super(runtime);
      key.copyFrom(keyArg);
      source.set(sourceArg);
    }
  }

  public static enum ShmAllocateResult_Tag {
    AllocationSucceeded,
    DigestDidNotMatch,
    AllocationFailed,
  }

  public static class AllocationSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public AllocationSucceeded_Body() {
      super(runtime);
    }
  }

  public static class DigestDidNotMatch_Body extends Struct {
    public final ShmKey _0 = new ShmKey();

    public DigestDidNotMatch_Body() {
      super(runtime);
    }
  }

  public static class AllocationFailed_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public AllocationFailed_Body() {
      super(runtime);
    }
  }

  public static class ShmAllocateResult_Body extends Union {
    public final AllocationSucceeded_Body allocation_succeeded = new AllocationSucceeded_Body();
    public final DigestDidNotMatch_Body digest_did_not_match = new DigestDidNotMatch_Body();
    public final AllocationFailed_Body allocation_failed = new AllocationFailed_Body();

    public ShmAllocateResult_Body() {
      super(runtime);
    }
  }

  public static class ShmAllocateResult extends Struct {
    public final Enum8<ShmAllocateResult_Tag> tag =
      new Enum8<ShmAllocateResult_Tag>(ShmAllocateResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmAllocateResult_Body body = new ShmAllocateResult_Body();

    public ShmAllocateResult() {
      super(runtime);
    }

    public ShmAllocateResult(jnr.ffi.Runtime runtime) {
      super(runtime);
    }
  }

  // shm_retrieve() types!
  public static class ShmRetrieveRequest extends Struct {
    public final ShmKey key;

    public ShmRetrieveRequest() {
      super(runtime);
      key = new ShmKey();
    }

    public ShmRetrieveRequest(ShmKey keyArg) {
      super(runtime);
      key = keyArg;
    }
  }

  public static enum ShmRetrieveResult_Tag {
    RetrieveSucceeded,
    RetrieveDidNotExist,
    RetrieveInternalError,
  }

  public static class RetrieveSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public RetrieveSucceeded_Body() {
      super(runtime);
    }
  }

  public static class RetrieveInternalError_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public RetrieveInternalError_Body() {
      super(runtime);
    }
  }

  public static class ShmRetrieveResult_Body extends Union {
    public final RetrieveSucceeded_Body retrieve_succeeded = new RetrieveSucceeded_Body();
    public final RetrieveInternalError_Body retrieve_internal_error =
      new RetrieveInternalError_Body();

    public ShmRetrieveResult_Body() {
      super(runtime);
    }
  }

  public static class ShmRetrieveResult extends Struct {
    public final Enum8<ShmRetrieveResult_Tag> tag =
      new Enum8<ShmRetrieveResult_Tag>(ShmRetrieveResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmRetrieveResult_Body body = new ShmRetrieveResult_Body();

    public ShmRetrieveResult() {
      super(runtime);
    }

    public ShmRetrieveResult(jnr.ffi.Runtime runtime) {
      super(runtime);
    }
  }

  // shm_delete() types!
  public static class ShmDeleteRequest extends Struct {
    public final ShmKey key;

    public ShmDeleteRequest() {
      super(runtime);
      key = new ShmKey();
    }

    public ShmDeleteRequest(ShmKey keyArg) {
      super(runtime);
      key = keyArg;
    }
  }

  public static enum ShmDeleteResult_Tag {
    DeletionSucceeded,
    DeleteDidNotExist,
    DeleteInternalError,
  }

  public static class DeleteSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public DeleteSucceeded_Body() {
      super(runtime);
    }
  }

  public static class DeleteInternalError_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public DeleteInternalError_Body() {
      super(runtime);
    }
  }

  public static class ShmDeleteResult_Body extends Union {
    public final DeleteInternalError_Body delete_internal_error =
      new DeleteInternalError_Body();

    public ShmDeleteResult_Body() {
      super(runtime);
    }
  }

  public static class ShmDeleteResult extends Struct {
    public final Enum8<ShmDeleteResult_Tag> tag =
      new Enum8<ShmDeleteResult_Tag>(ShmDeleteResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmDeleteResult_Body body = new ShmDeleteResult_Body();

    public ShmDeleteResult() {
      super(runtime);
    }

    public ShmDeleteResult(jnr.ffi.Runtime runtime) {
      super(runtime);
    }
  }
}
