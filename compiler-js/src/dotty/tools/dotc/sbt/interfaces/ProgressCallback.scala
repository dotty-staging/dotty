package dotty.tools.dotc.sbt.interfaces

import dotty.tools.dotc.CompilationUnit

/** Scala replacement for the Java ProgressCallback interface,
 *  removing xsbti dependency for Scala.js.
 */
trait ProgressCallback {
  def cancel(): Unit = ()
  def isCancelled(): Boolean = false
  def informUnitStarting(phase: String, unit: CompilationUnit): Unit = ()
  def progress(current: Int, total: Int, currPhase: String, nextPhase: String): Boolean = true
}
