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

  public static enum ShmDeleteResult_Tag {
    DeletionSucceeded,
    DeleteDidNotExist,
    DeleteInternalError,
  }
}
