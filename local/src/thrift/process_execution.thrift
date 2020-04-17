namespace rs upc.local.thrift_rust.process_execution
namespace java upc.local.thrift_java.process_execution

include "directory.thrift"
include "file.thrift"
include "glob_matching.thrift"


struct BasicExecuteProcessRequest {
  1: optional list<string> argv,
  2: optional map<string, string> env,
  3: optional directory.DirectoryDigest input_files,
  4: optional glob_matching.PathGlobs output_globs,
}

struct VirtualizedExecuteProcessRequest {
  // These processes will all succeed or fail as a group, when more than one is requested.
  1: optional list<BasicExecuteProcessRequest> conjoined_requests,
  // This process will be started first, in the background, before any children are created.
  2: optional BasicExecuteProcessRequest daemon_execution_request,
}

struct ExecuteProcessResult {
  1: optional i32 exit_code,
  2: optional file.FileDigest stdout,
  3: optional file.FileDigest stderr,
  4: optional directory.DirectoryDigest output_directory_digest,
}

enum ProcessExecutionErrorCode {
  PROCESS_FAILED = 1,
  COULD_NOT_LOCATE_INPUT_FILES = 2,
  INTERNAL_ERROR = 3,
}
exception ProcessExecutionError {
  1: optional ProcessExecutionErrorCode error_code,
  2: optional string description,
}

service ProcessExecutionService {
  ExecuteProcessResult executeProcesses(1: VirtualizedExecuteProcessRequest execute_process_request)
    throws (1: ProcessExecutionError process_execution_error)
}

// This is what the VirtualCLI subprocesses interact with to signal that they have exited!
// Note: This /could/ be merged into the ProcessExecutionService above, but the clients of process
// execution (build tools) vs process reaping (subprocesses like compilers) are so different that it
// seems to make sense to separate them here.
service ProcessReapService {
  void reapProcess(1: ExecuteProcessResult process_result)
}
