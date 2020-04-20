package upc.local.memory;


public class LibMemoryEnums {
  public static enum ShmAllocateResultStatus_Tag {
    AllocationSucceeded,
    DigestDidNotMatch,
    AllocationFailed,
  }

  public static enum ShmRetrieveResultStatus_Tag {
    RetrieveSucceeded,
    RetrieveDidNotExist,
    RetrieveInternalError,
  }

  public static enum ShmDeleteResultStatus_Tag {
    DeletionSucceeded,
    DeleteDidNotExist,
    DeleteInternalError,
  }
}
