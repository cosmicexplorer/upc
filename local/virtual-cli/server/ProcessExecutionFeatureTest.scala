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
  // running the process executions that are explicitly tested in this file1
  test("ProcessExecutionServer#execute processes correctly") {
    await(client.executeProcesses(???))
  }
}
