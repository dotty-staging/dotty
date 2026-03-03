package dotty.tools.dotc.profile

import java.io.Closeable
import java.nio.file.Path

/** Stub ChromeTrace for Scala.js - no file I/O available */
final class ChromeTrace(f: Path) extends Closeable {
  override def close(): Unit = ()

  def traceDurationEvent(name: String, startNanos: Long, durationNanos: Long, tid: String = "", pidSuffix: String = ""): Unit = ()
  def traceCounterEvent(name: String, counterName: String, count: Long, processWide: Boolean): Unit = ()
  def traceDurationEventStart(cat: String, name: String, colour: String = "", pidSuffix: String = ""): Unit = ()
  def traceDurationEventEnd(cat: String, name: String, colour: String = "", pidSuffix: String = ""): Unit = ()
}
