package upc.local.virtual_cli.client

import upc.local._
import upc.local.directory
import upc.local.memory

import ammonite.ops._

import scala.concurrent.{blocking, ExecutionContext, Future}


case class IOServicesConfig(
  initialDigest: DirectoryDigest,
  executor: ExecutionContext,
)

sealed abstract class IOServicesError(message: String) extends Exception(message)
case class InitialFileMappingFailed(message: String) extends IOServicesError(message)
case class UploadBytesFailed(message: String) extends IOServicesError(message)
case class UploadVFSFailed(message: String) extends IOServicesError(message)


class IOServices(cwd: Path, config: IOServicesConfig) {
  implicit val executor: ExecutionContext = config.executor

  def readFileMapping(): Future[FileMapping] = Future { blocking {
    val req = directory.ExpandDirectoriesRequest(Seq(config.initialDigest))

    val mapping = directory.DirectoryMapping.expand(req).get match {
      case directory.ExpandSucceeded(directory.ExpandDirectoriesMapping(mapping)) => mapping
    }
    if (mapping.size != 1) {
      throw InitialFileMappingFailed(s"initial mapping $mapping returned more than one digest!")
    }

    val (digest, pathStats) = mapping.toSeq.apply(0)
    if (digest != config.initialDigest) {
      throw InitialFileMappingFailed(
        s"returned digest $digest from attempted initial mapping did not match argument ${config.initialDigest}!")
    }

    FileMapping.fromPathStats(cwd, pathStats).get
  }}

  def uploadBytes(bytes: Array[Byte]): Future[ShmKey] = Future { blocking {
    val inputMapping = memory.MemoryMapping.fromArray(bytes)
    val key = memory.Shm.getKey(memory.ShmGetKeyRequest(inputMapping)).get
    val allocateRequest = memory.ShmAllocateRequest(key, inputMapping)
    val outKey = memory.Shm.allocate(allocateRequest).get match {
      case memory.AllocationSucceeded(key, _) => key
    }
    // TODO: This is already tested in the directory crate itself.
    if (outKey != key) {
      throw UploadBytesFailed(s"key $outKey from allocation did not match input key $key!")
    }
    outKey
  }}

  def uploadVFS(fileMapping: FileMapping): Future[DirectoryDigest] = Future { blocking {
    val pathStats = fileMapping.intoPathStats(cwd).get
    val uploadRequest = directory.UploadDirectoriesRequest(Seq(pathStats))
    val mapping = directory.DirectoryMapping.upload(uploadRequest).get match {
      case directory.UploadSucceeded(directory.ExpandDirectoriesMapping(mapping)) => mapping
    }
    val (digest, _stats) = mapping.toSeq.apply(0)
    digest
  }}

  def writeIOState(
    fileMapping: FileMapping,
    stdioResults: StdioResults,
  ): Future[IOFinalState] = for {
    vfsDigest <- uploadVFS(fileMapping)
    stdoutDigest <- uploadBytes(stdioResults.stdout)
    stderrDigest <- uploadBytes(stdioResults.stderr)
  } yield IOFinalState(
    vfsDigest = vfsDigest,
    stdout = stdoutDigest,
    stderr = stderrDigest,
  )

  def reapProcess(
    exitCode: ExitCode,
    stdioResults: StdioResults,
    fileMapping: FileMapping,
  ): Future[CompleteVirtualizedProcessResult] = for {
    ioState <- writeIOState(fileMapping, stdioResults)
  } yield CompleteVirtualizedProcessResult(exitCode, ioState)
}
