package upc.local.memory;

import jnr.ffi.*;
import jnr.ffi.types.*;

public class LibMemory {
  public static LibMemoryIface libMemoryInstance =
    LibraryLoader.create(LibMemoryIface.class).load("memory");
  public static jnr.ffi.Runtime runtime = jnr.ffi.Runtime.getRuntime(libMemoryInstance);

  public interface LibMemoryIface {
    public ShmResult allocate_shm(ShmRequest request);
  }

  public enum Permission {
    Read,
    Write,
  }

  public class ShmKey extends Struct {
    public final Pointer string = new Pointer();
    public final u_int32_t length = new u_int32_t();

    public ShmKey() {
      super(runtime);
    }
  }

  public class ShmRequest extends Struct {
    public final ShmKey key = new ShmKey();
    public final u_int64_t size_bytes = new u_int64_t();
    public final Enum8<Permission> permission = new Enum8<Permission>(Permission.class);

    public ShmRequest() {
      super(runtime);
    }
  }

  public enum ShmResult_Tag {
    Succeeded,
    Failed,
  }

  public class Succeeded_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public Succeeded_Body() {
      super(runtime);
    }
  }

  public class Failed_Body extends Struct {
    public final Pointer _0 = new Pointer();

    public Failed_Body() {
      super(runtime);
    }
  }

  public class ShmResult_Body extends Union {
    public final Succeeded_Body succeeded = new Succeeded_Body();
    public final Failed_Body failed = new Failed_Body();

    public ShmResult_Body() {
      super(runtime);
    }
  }

  public class ShmResult extends Struct {
    public final Enum8<ShmResult_Tag> tag = new Enum8<ShmResult_Tag>(ShmResult_Tag.class);
    public final ShmResult_Body body = new ShmResult_Body();

    public ShmResult() {
      super(runtime);
    }
  }
}
