package scala.tools.nsc.reporters

import scala.collection.mutable
import scala.reflect.internal.Reporter
import scala.reflect.internal.util.Position

/** Scala 3 port: test-only stand-in for the Scala 2 compiler's StoreReporter, providing
 *  just the surface used by the vendored scala-reflect tests.
 */
class StoreReporter(settings: Any) extends Reporter {
  case class Info(pos: Position, msg: String, severity: Severity) {
    override def toString: String = s"pos: $pos $msg $severity"
  }
  val infos = new mutable.LinkedHashSet[Info]

  // severity counts are maintained by Reporter.filteredInfo before info0 is invoked
  protected def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit =
    infos += Info(pos, msg, severity)

  override def reset(): Unit = {
    super.reset()
    infos.clear()
  }
}
