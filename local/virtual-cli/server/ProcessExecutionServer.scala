package upc.local.virtual_cli.server

import com.google.inject.Module
import com.twitter.finatra.thrift.ThriftServer
import com.twitter.finatra.thrift.routing.ThriftRouter


object ProcessExecutionServerMain extends ProcessExecutionServer

object ProcessExecutionServer {
  val defaultThriftPortNum: Int = 9090
}
class ProcessExecutionServer extends ThriftServer {
  import ProcessExecutionServer._

  override val defaultThriftPort: String = s":$defaultThriftPortNum"

  override val modules: Seq[Module] = Seq()

  override def configureThrift(router: ThriftRouter): Unit = {
    router
      .add[ProcessExecutionController]
  }
}
