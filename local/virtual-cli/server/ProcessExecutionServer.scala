package upc.local.virtual_cli.server

import com.google.inject.Module
import com.twitter.finagle.Service
import com.twitter.finatra.thrift.ThriftServer
import com.twitter.finatra.thrift.routing.ThriftRouter


object ProcessExecutionServerMain extends ProcessExecutionServer

class ProcessExecutionServer extends ThriftServer {
  override val modules: Seq[Module] = Seq()

  override def configureThrift(router: ThriftRouter): Unit = {
    router
      .add[ProcessExecutionController]
  }
}
