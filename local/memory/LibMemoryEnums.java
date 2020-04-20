package upc.local.memory;


public class LibMemoryEnums {
  public static enum ShmAllocateResult_Tag {
    AllocationSucceeded,
    DigestDidNotMatch,
    AllocationFailed,
  }

  public static enum ShmRetrieveResult_Tag {
    RetrieveSucceeded,
    RetrieveDidNotExist,
    RetrieveInternalError,
  }

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
