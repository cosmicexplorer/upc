package upc.local.virtual_cli.server

import upc.local.thriftscala.process_execution._

import com.twitter.finatra.thrift.EmbeddedThriftServer
import com.twitter.inject.server.FeatureTest
import com.twitter.util.Future


class ProcessExecutionFeatureTest extends FeatureTest {
  override val server = new EmbeddedThriftServer(new ProcessExecutionServer)

  lazy val client: ProcessExecutionService[Future] =
    server.thriftClient[ProcessExecutionService[Future]](clientId = "client123")

  // NB: reapProcess is not tested because the return value is void! It will be implicitly tested by
  // running the process executions that are explicitly tested in this file!
  test("ProcessExecutionServer#executes a single process correctly") {
    await(client.executeProcesses(???))
  }

  test("#executes multiple concurrent processes and cats the stdout") {
    // write files "a.txt" and "b.txt" into the initial digests, make subprocesses cat them, and
    // then cat the stdout of those subprocesses.
    await(client.executeProcesses(???))
  }

  test("#executes multiple concurrent processes and merges the vfs digests") {
    // write files "a.txt" and "b.txt" into the initial digests, make subprocesses write to two NEW
    // files, and then read the merged digest of the output.
    await(client.executeProcesses(???))
  }
}
