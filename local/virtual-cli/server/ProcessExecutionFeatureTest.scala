package upc.local.virtual_cli.server

import upc.local.thriftscala.process_execution._

import com.twitter.finatra.thrift.EmbeddedThriftServer
import com.twitter.inject.server.FeatureTest
import com.twitter.util.Future


class ProcessExecutionFeatureTest extends FeatureTest {
  override val server = new EmbeddedThriftServer(new ProcessExecutionServer)

  lazy val client: ProcessExecutionService[Future] =
    server.thriftClient[ProcessExecutionService[Future]](clientId = "client123")

  test("ProcessExecutionServer#reaps processes correctly") {
    await(client.reapProcess(???))
  }
}
