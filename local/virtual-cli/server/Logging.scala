package upc.local.virtual_cli.server

import java.io.PrintStream


class Logger(stream: PrintStream = System.err) {
  def info(msg: String): Unit = {
    log(s"INFO: $msg")
  }
  def debug(msg: String): Unit = {
    log(s"DEBUG: $msg")
  }
  def warn(msg: String): Unit = {
    log(s"WARN: $msg")
  }
  def log(msg: String): Unit = {
    stream.write(s"${msg}\n".getBytes)
  }
}
