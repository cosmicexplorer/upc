package upc.local.memory;

import jnr.ffi.*;
import jnr.ffi.types.*;


public class LibMemory {
  public static LibMemoryIface libMemoryInstance =
    LibraryLoader.create(LibMemoryIface.class).load("memory");
  public static jnr.ffi.Runtime runtime = jnr.ffi.Runtime.getRuntime(libMemoryInstance);

  public interface LibMemoryIface {
    public ShmAllocateResult shm_allocate(ShmAllocateRequest request);
    public ShmRetrieveResult shm_retrieve(ShmRetrieveRequest request);
    public ShmDeleteResult shm_delete(ShmDeleteRequest request);
  }

  // common types!
  public class Fingerprint extends Struct {
    public final u_int8_t[] _0 = arrayOf(runtime, u_int8_t.class, 32);

    public Fingerprint() {
      super(runtime);
    }
  }

  public class ShmKey extends Struct {
    public final Fingerprint fingerprint = new Fingerprint();
    public final u_int32_t length = new u_int32_t();

    public ShmKey() {
      super(runtime);
    }
  }

  // shm_allocate() types!
  public class ShmAllocateRequest extends Struct {
    public final ShmKey key = new ShmKey();
    public final Pointer source = new Pointer();

    public ShmAllocateRequest() {
      super(runtime);
    }
  }

  public enum ShmAllocateResult_Tag {
    AllocationSucceeded,
    DigestDidNotMatch,
    AllocationFailed,
  }

  public class AllocationSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public AllocationSucceeded_Body() {
      super(runtime);
    }
  }

  public class DigestDidNotMatch_Body extends Struct {
    public final ShmKey _0 = new ShmKey();

    public DigestDidNotMatch_Body() {
      super(runtime);
    }
  }

  public class AllocationFailed_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public AllocationFailed_Body() {
      super(runtime);
    }
  }

  public class ShmAllocateResult_Body extends Union {
    public final AllocationSucceeded_Body allocation_succeeded = new Succeeded_Body();
    public final DigestDidNotMatch_Body digest_did_not_match = new DigestDidNotMatch_Body();
    public final AllocationFailed_Body allocation_failed = new AllocationFailed_Body();

    public ShmAllocateResult_Body() {
      super(runtime);
    }
  }

  public class ShmAllocateResult extends Struct {
    public final Enum8<ShmAllocateResult_Tag> tag =
      new Enum8<ShmAllocateResult_Tag>(ShmAllocateResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmAllocateResult_Body body = new ShmAllocateResult_Body();

    public ShmAllocateResult() {
      super(runtime);
    }
  }

  // shm_retrieve() types!
  public class ShmRetrieveRequest extends Struct {
    public final ShmKey key = new ShmKey();

    public ShmRetrieveRequest() {
      super(runtime);
    }
  }

  public enum ShmRetrieveResult_Tag {
    RetrieveSucceeded,
    RetrieveDidNotExist,
    RetrieveInternalError,
  }

  public class RetrieveSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public RetrieveSucceeded_Body() {
      super(runtime);
    }
  }

  public class RetrieveInternalError_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public RetrieveInternalError_Body() {
      super(runtime);
    }
  }

  public class ShmRetrieveResult_Body extends Union {
    public final RetrieveSucceeded_Body retrieve_succeeded = new RetrieveSucceeded_Body();
    public final RetrieveInternalError_Body retrieve_internal_error =
      new RetrieveInternalError_Body();

    public ShmRetrieveResult_Body() {
      super(runtime);
    }
  }

  public class ShmRetrieveResult extends Struct {
    public final Enum8<ShmRetrieveResult_Tag> tag =
      new Enum8<ShmRetrieveResult_Tag>(ShmRetrieveResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmRetrieveResult_Body body = new ShmRetrieveResult_Body();

    public ShmRetrieveResult() {
      super(runtime);
    }
  }

  // shm_delete() types!
  public class ShmDeleteRequest extends Struct {
    public final ShmKey key = new ShmKey();

    public ShmDeleteRequest() {
      super(runtime);
    }
  }

  public enum ShmDeleteResult_Tag {
    DeletionSucceeded,
    DeleteDidNotExist,
    DeleteInternalError,
  }

  public class DeleteSucceeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public DeleteSucceeded_Body() {
      super(runtime);
    }
  }

  public class DeleteInternalError_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public DeleteInternalError_Body() {
      super(runtime);
    }
  }

  public class ShmDeleteResult_Body extends Union {
    public final DeleteInternalError_Body delete_internal_error =
      new DeleteInternalError_Body();

    public ShmDeleteResult_Body() {
      super(runtime);
    }
  }

  public class ShmDeleteResult extends Struct {
    public final Enum8<ShmDeleteResult_Tag> tag =
      new Enum8<ShmDeleteResult_Tag>(ShmDeleteResult_Tag.class);
    // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
    public final ShmDeleteResult_Body body = new ShmDeleteResult_Body();

    public ShmDeleteResult() {
      super(runtime);
    }
  }
}
