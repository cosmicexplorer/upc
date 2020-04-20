package upc.local.memory;


public class LibMemoryEnums {
  public static enum ShmAllocateResult_Tag {
    AllocationSucceeded,
    DigestDidNotMatch,
    AllocationFailed,
  }

  // // shm_retrieve() types!
  // public static class ShmRetrieveRequest extends Struct {
  //   public final ShmKey key;

  //   public ShmRetrieveRequest() {
  //     super(runtime);
  //     key = new ShmKey();
  //   }

  //   public ShmRetrieveRequest(ShmKey keyArg) {
  //     super(runtime);
  //     key = keyArg;
  //   }
  // }

  // public static enum ShmRetrieveResult_Tag {
  //   RetrieveSucceeded,
  //   RetrieveDidNotExist,
  //   RetrieveInternalError,
  // }

  // public static class RetrieveSucceeded_Body extends Struct {
  //   public final Pointer _0 = new Pointer();

  //   public RetrieveSucceeded_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class RetrieveInternalError_Body extends Struct {
  //   public final Pointer _0 = new Pointer();

  //   public RetrieveInternalError_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class ShmRetrieveResult_Body extends Union {
  //   public final RetrieveSucceeded_Body retrieve_succeeded = new RetrieveSucceeded_Body();
  //   public final RetrieveInternalError_Body retrieve_internal_error =
  //     new RetrieveInternalError_Body();

  //   public ShmRetrieveResult_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class ShmRetrieveResult extends Struct {
  //   public final Enum8<ShmRetrieveResult_Tag> tag =
  //     new Enum8<ShmRetrieveResult_Tag>(ShmRetrieveResult_Tag.class);
  //   // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
  //   public final ShmRetrieveResult_Body body = new ShmRetrieveResult_Body();

  //   public ShmRetrieveResult() {
  //     super(runtime);
  //   }

  //   public ShmRetrieveResult(jnr.ffi.Runtime runtime) {
  //     super(runtime);
  //   }
  // }

  // // shm_delete() types!
  // public static class ShmDeleteRequest extends Struct {
  //   public final ShmKey key;

  //   public ShmDeleteRequest() {
  //     super(runtime);
  //     key = new ShmKey();
  //   }

  //   public ShmDeleteRequest(ShmKey keyArg) {
  //     super(runtime);
  //     key = keyArg;
  //   }
  // }

  // public static enum ShmDeleteResult_Tag {
  //   DeletionSucceeded,
  //   DeleteDidNotExist,
  //   DeleteInternalError,
  // }

  // public static class DeleteSucceeded_Body extends Struct {
  //   public final Pointer _0 = new Pointer();

  //   public DeleteSucceeded_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class DeleteInternalError_Body extends Struct {
  //   public final Pointer _0 = new Pointer();

  //   public DeleteInternalError_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class ShmDeleteResult_Body extends Union {
  //   public final DeleteInternalError_Body delete_internal_error =
  //     new DeleteInternalError_Body();

  //   public ShmDeleteResult_Body() {
  //     super(runtime);
  //   }
  // }

  // public static class ShmDeleteResult extends Struct {
  //   public final Enum8<ShmDeleteResult_Tag> tag =
  //     new Enum8<ShmDeleteResult_Tag>(ShmDeleteResult_Tag.class);
  //   // Note: this is an anonymous union in test.h, so the `body` identifier does not exist!
  //   public final ShmDeleteResult_Body body = new ShmDeleteResult_Body();

  //   public ShmDeleteResult() {
  //     super(runtime);
  //   }

  //   public ShmDeleteResult(jnr.ffi.Runtime runtime) {
  //     super(runtime);
  //   }
  // }
}
