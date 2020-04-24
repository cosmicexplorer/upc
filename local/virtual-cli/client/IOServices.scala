package upc.local.virtual_cli.client

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


class IOServices(config: IOServicesConfig) {
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

    FileMapping.fromPathStats(pathStats)
  }}

  def uploadBytes(bytes: Array[Byte]): Future[FileDigest] = Future { blocking {
    val inputMapping = memory.MemoryMapping.fromArray(bytes)
    val key = memory.Shm.getKey(memory.ShmGetKeyRequest(mapping)).get
    val allocateRequest = memory.ShmAllocateRequest(key, inputMapping)
    val outputMapping = memory.Shm.allocate(allocateRequest).get match {
      memory.AllocationSucceeded(_, _) => mapping
    }

  }}

  def writeIOState(
    fileMapping: FileMapping,
    stdioResults: StdioResults,
  ): Future[IOFinalState] = {
    val uploadVFS: Future[DirectoryDigest] = Future { blocking { ??? } }
    val uploadStdout: Future[FileDigest] = Future { blocking { ??? } }
    val uploadStderr: Future[FileDigest] = Future { blocking { ??? } }

    for {
      vfsDigest <- uploadVFS
      stdoutDigest <- uploadStdout
      stderrDigest <- uploadStderr
    } yield IOFinalState(
      vfsDigest = vfsDigest,
      stdout = stdoutDigest,
      stderr = stderrDigest,
    )
  }

  def reapProcess(
    exitCode: ExitCode,
    stdioResults: StdioResults,
    fileMapping: FileMapping,
  ): Future[CompleteVirtualizedProcessResult] = for {
    ioState <- writeIOState(fileMapping, stdioResults)
  } yield CompleteVirtualizedProcessResult(exitCode, ioState)
}
