package upc.local.virtual_cli.client.standalone.process_executor

import upc.local._
import upc.local.thrift_socket._
import upc.local.virtual_cli.client._


object ProcessExecutor extends MainWrapper {
  override def virtualizedMainMethod(args: Array[String]): Int = {
    val (exeClient, reapClient, initialDigest, cmd) = args match {
      case Array(
        exeServerPath,
        reapServerPath,
        initialFingerprint,
        initialSizeBytes,
        "--",
        cmd @ _*) => (
        ThriftUnixClient(exeServerPath).get,
        ThriftUnixClient(reapServerPath).get,
        DirectoryDigest(Digest.fromFingerprintHex(initialFingerprint, initialSizeBytes.toInt).get),
        cmd,
      )
      case x => throw new RuntimeException(s"unrecognized arguments $x. expected:  [VAR=VAL...] command [args...]")
    }

    cmd.map
  }
}
