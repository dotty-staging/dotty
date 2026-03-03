package dotty.tools.dotc.profile

import scala.language.unsafeNulls

import java.io.{BufferedWriter, Writer}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{OpenOption, Path}

/** Stub FileUtils for Scala.js */
object FileUtils {
  def newAsyncBufferedWriter(path: Path, charset: Charset = StandardCharsets.UTF_8.nn, options: Array[OpenOption] = Array.empty, threadsafe: Boolean = false): LineWriter = {
    throw new UnsupportedOperationException("FileUtils.newAsyncBufferedWriter not supported on Scala.js")
  }
  def newAsyncBufferedWriter(underlying: Writer, threadsafe: Boolean): LineWriter = {
    throw new UnsupportedOperationException("FileUtils.newAsyncBufferedWriter not supported on Scala.js")
  }

  sealed abstract class LineWriter extends Writer {
    def newLine(): Unit
  }
}
