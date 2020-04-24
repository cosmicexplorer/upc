namespace rs upc.local.thrift_rust.process_execution
#@namespace scala upc.local.thriftscala.process_execution

struct ShmKey {
  1: optional string fingerprint,
  2: optional i64 size_bytes,
}

struct DirectoryDigest {
  1: optional string fingerprint,
  2: optional i64 size_bytes,
}

struct PathGlobs {
  1: optional list<string> include_globs,
  2: optional list<string> exclude_globs,
}

struct BasicExecuteProcessRequest {
  1: optional list<string> argv,
  2: optional map<string, string> env,
  3: optional DirectoryDigest input_files,
  4: optional PathGlobs output_globs,
}

struct VirtualizedExecuteProcessRequest {
  // These processes will all succeed or fail as a group, when more than one is requested.
  1: optional list<BasicExecuteProcessRequest> conjoined_requests,
  // This process will be started first, in the background, before any children are created.
  2: optional BasicExecuteProcessRequest daemon_execution_request,
}

struct ExecuteProcessResult {
  1: optional i32 exit_code,
  2: optional ShmKey stdout,
  3: optional ShmKey stderr,
  4: optional DirectoryDigest output_directory_digest,
  // TODO: zipkin span id!!
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

  void reapProcess(1: ExecuteProcessResult process_result)
}
