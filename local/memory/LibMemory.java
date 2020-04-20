package upc.local.memory;

import jnr.ffi.*;
import jnr.ffi.types.*;


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
  public static class FingerprintError extends Error {
    public FingerprintError(String message) {
      super(message);
    }
    public FingerprintError(String message, Throwable cause) {
      super(message, cause);
    }
  }
  public static class Fingerprint extends Struct {
    public final Padding _0;

    public static final int FINGERPRINT_LENGTH = 32;

    public Fingerprint() {
      super(runtime);
      _0 = new Padding(NativeType.UCHAR, FINGERPRINT_LENGTH);
    }

    public Fingerprint(byte[] bytes) throws FingerprintError {
      super(runtime);
      _0 = new Padding(NativeType.UCHAR, FINGERPRINT_LENGTH);
      setBytes(bytes);
    }

    public Fingerprint(Fingerprint otherFingerprint) throws FingerprintError {
      super(runtime);
      _0 = new Padding(NativeType.UCHAR, FINGERPRINT_LENGTH);
      copyFrom(otherFingerprint);
    }

    public void setBytes(byte[] bytes) throws FingerprintError {
      if (bytes.length != FINGERPRINT_LENGTH) {
        throw new FingerprintError("fingerprint bytes must be " +
                                   Integer.toString(FINGERPRINT_LENGTH) +
                                   " bytes long -- instead received + " +
                                   Integer.toString(bytes.length));
      }
      jnr.ffi.Pointer paddingBytes = _0.getMemory();
      paddingBytes.put(0, bytes, 0, bytes.length);
    }

    public byte[] getBytesCopy() {
      byte[] bytes = new byte[FINGERPRINT_LENGTH];
      jnr.ffi.Pointer paddingBytes = _0.getMemory();
      paddingBytes.get(0, bytes, 0, FINGERPRINT_LENGTH);
      return bytes;
    }

    /* FIXME: can we avoid bubbling up `throws FingerprintError` here since we know that the other
     * fingerprint will have the correct number of bytes? */
    public void copyFrom(Fingerprint otherFingerprint) throws FingerprintError {
      byte[] otherBytes = otherFingerprint.getBytesCopy();
      setBytes(otherBytes);
    }
  }

  public static class ShmKeyError extends Error {
    public ShmKeyError(String message) {
      super(message);
    }
    public ShmKeyError(String message, Throwable cause) {
      super(message, cause);
    }
  }
  public static class ShmKey extends Struct {
    public final Fingerprint fingerprint;
    public final u_int64_t length;

    public ShmKey() {
      super(runtime);
      fingerprint = new Fingerprint();
      length = new u_int64_t();
    }

    public ShmKey(Fingerprint fingerprintArg, long lengthArg) throws ShmKeyError {
      super(runtime);
      fingerprint = new Fingerprint();
      length = new u_int64_t();
      setFingerprint(fingerprintArg);
      setLength(lengthArg);
    }

    public ShmKey(ShmKey otherShmKey) throws ShmKeyError {
      super(runtime);
      fingerprint = new Fingerprint();
      length = new u_int64_t();
      copyFrom(otherShmKey);
    }

    public void setFingerprint(Fingerprint otherFingerprint) throws ShmKeyError {
      try {
        fingerprint.copyFrom(otherFingerprint);
      } catch (FingerprintError e) {
        throw new ShmKeyError("fingerprint encoding error", e);
      }
    }

    public Fingerprint getFingerprint() {
      return fingerprint;
    }

    public void setLength(long otherLength) throws ShmKeyError {
      if (otherLength < 0) {
        throw new ShmKeyError("length cannot be negative -- was " + Long.toString(otherLength));
      }
      length.set(otherLength);
    }

    public long getLength() {
      return length.get();
    }

    public void copyFrom(ShmKey otherShmKey) throws ShmKeyError {
      setFingerprint(otherShmKey.getFingerprint());
      setLength(otherShmKey.getLength());
    }
  }

  // shm_get_key() types!
  public static class ShmGetKeyRequest extends Struct {
    public final u_int64_t size;
    public final Pointer source;

    public ShmGetKeyRequest() {
      super(runtime);
      size = new u_int64_t();
      source = new Pointer();
    }

    public ShmGetKeyRequest(long sizeArg, jnr.ffi.Pointer sourceArg) throws ShmKeyError {
      super(runtime);
      if (sizeArg < 0) {
        throw new ShmKeyError("size cannot be negative -- was " + Long.toString(sizeArg));
      }
      size = new u_int64_t();
      size.set(sizeArg);
      source = new Pointer();
      source.set(sourceArg);
    }
  }

  // shm_allocate() types!
  public static class ShmAllocateRequest extends Struct {
    public final ShmKey key;
    public final Pointer source;

    public ShmAllocateRequest() {
      super(runtime);
      key = new ShmKey();
      source = new Pointer();
    }

    public ShmAllocateRequest(ShmKey keyArg, jnr.ffi.Pointer sourceArg) {
      super(runtime);
      key = keyArg;
      source = new Pointer();
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
