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

struct ReducedExecuteProcessRequest {
  1: optional list<string> argv,
  2: optional map<string, string> env,
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
  2: optional ReducedExecuteProcessRequest daemon_execution_request,
}

struct ExecuteProcessResult {
  1: optional i32 exit_code,
  2: optional ShmKey stdout,
  3: optional ShmKey stderr,
  4: optional DirectoryDigest output_directory_digest,
  // TODO: zipkin span id!!
}

// This is provided to invoked subprocesses via the environment (or something), so that it can be
// sent back when the process reaps itself.
struct SubprocessRequestId {
  1: optional string id,
}

struct ProcessReapResult {
  1: optional ExecuteProcessResult exe_result,
  2: optional SubprocessRequestId id,
}

service ProcessExecutionService {
  ExecuteProcessResult executeProcesses(1: VirtualizedExecuteProcessRequest execute_process_request)

  // NB: These two methods *would* be in different services, but Finatra only supports a single
  // controller per thrift server, and hence only a single thrift service per server.
  void reapProcess(1: ProcessReapResult process_reap_result)
}
