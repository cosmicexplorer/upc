package upc.local

import java.io.IOException
import javax.xml.bind.DatatypeConverter
import scala.util.Try


sealed abstract class EncodingError(message: String) extends IOException(message)
case class BadDigest(message: String) extends EncodingError(message)


case class Digest(fingerprint: Array[Byte], length: Long) {
  if (fingerprint.length != 32) {
    throw BadDigest(s"invalid fingerprint: must be 32 bytes (was: $fingerprint)")
  }
  if (length < 0) {
    throw BadDigest(s"invalid Digest: length must be non-negative (was: $this)")
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
    s"Digest(length=$length, fingerprint=$fingerprintHex)"
  }
}
object Digest {
  def fromFingerprintHex(hex: String, length: Long): Try[Digest] = Try(Digest(
    fingerprint = DatatypeConverter.parseHexBinary(hex),
    length = length))

  val EMPTY_FINGERPRINT: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def empty: Digest = fromFingerprintHex(EMPTY_FINGERPRINT, 0).get
}


case class PathGlobs(
  include: Seq[String],
  exclude: Seq[String],
)


case class DirectoryDigest(digest: Digest)

case class ShmKey(digest: Digest)

case class IOFinalState(
  vfsDigest: DirectoryDigest,
  stdout: ShmKey,
  stderr: ShmKey,
)

case class ExitCode(code: Int)

case class CompleteVirtualizedProcessResult(
  exitCode: ExitCode,
  ioState: IOFinalState,
)

case class SubprocessRequestId(inner: String)
