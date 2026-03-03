package java.nio.channels

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.{OpenOption, Path}

abstract class FileChannel extends Channel {
  def write(src: ByteBuffer, position: Long): Int
  def close(): Unit
}

object FileChannel {
  def open(path: Path, options: java.util.Set[_ <: OpenOption]): FileChannel =
    throw new IOException("FileChannel.open not supported on Scala.js")
}

trait Channel extends java.io.Closeable

class ClosedByInterruptException extends IOException("Channel closed by interrupt")
