package upc.local

import java.io.IOException
import javax.xml.bind.DatatypeConverter
import scala.util.Try


sealed abstract class EncodingError(message: String) extends IOException(message)
case class BadDigest(message: String) extends EncodingError(message)


// Memory and VFS
case class Digest(fingerprint: Array[Byte], sizeBytes: Long) {
  if (fingerprint.length != 32) {
    throw BadDigest(s"invalid fingerprint: must be 32 bytes (was: $fingerprint)")
  }
  if (sizeBytes < 0) {
    throw BadDigest(s"invalid Digest: sizeBytes must be non-negative (was: $this)")
  }

  lazy val fingerprintHex: String = DatatypeConverter.printHexBinary(fingerprint).toLowerCase

  override def equals(other: Any): Boolean = {
    Option(other.asInstanceOf[Digest]) match {
      case None => false
      case Some(Digest(fp, len)) => fingerprint.zip(fp).map { case (fp1, fp2) => fp1 == fp2 }
          .fold(true) { case (acc, cur) => acc && cur }
    }
  }

  override def toString: String = {
    s"Digest(sizeBytes=$sizeBytes, fingerprint=$fingerprintHex)"
  }
}
object Digest {
  def fromFingerprintHex(hex: String, sizeBytes: Long): Try[Digest] = Try(Digest(
    fingerprint = DatatypeConverter.parseHexBinary(hex),
    sizeBytes = sizeBytes))

  val EMPTY_FINGERPRINT: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def empty: Digest = fromFingerprintHex(EMPTY_FINGERPRINT, 0).get
}

case class DirectoryDigest(digest: Digest)

case class ShmKey(digest: Digest)


// Process Execution
case class PathGlobs(
  include: Seq[String],
  exclude: Seq[String] = Seq(),
)

case class ReducedExecuteProcessRequest(
  argv: Seq[String],
  env: Map[String, String] = Map.empty,
  inputFiles: Option[DirectoryDigest] = None,
)

case class BasicExecuteProcessRequest(
  baseRequest: ReducedExecuteProcessRequest,
  outputGlobs: Option[PathGlobs] = None,
)

case class VirtualizedExecuteProcessRequest(
  conjoinedRequests: Seq[BasicExecuteProcessRequest],
  daemonExecutionRequest: Option[ReducedExecuteProcessRequest] = None,
)

case class IOFinalState(
  vfsDigest: DirectoryDigest,
  stdout: ShmKey,
  stderr: ShmKey,
)

case class ExitCode(code: Int)

case class ExecuteProcessResult(
  exitCode: ExitCode,
  ioState: IOFinalState,
)

case class SubprocessRequestId(inner: String)

case class ProcessReapResult(
  exeResult: ExecuteProcessResult,
  id: SubprocessRequestId,
)
