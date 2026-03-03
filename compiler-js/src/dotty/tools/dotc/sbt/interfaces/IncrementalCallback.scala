package dotty.tools.dotc.sbt.interfaces

import dotty.tools.dotc.util.SourceFile
import java.util.EnumSet
import java.nio.file.Path

/** Scala replacement for the Java IncrementalCallback interface,
 *  removing the xsbti dependency for Scala.js.
 */
trait IncrementalCallback {
  def api(sourceFile: SourceFile, classApi: Any): Unit = ()
  def startSource(sourceFile: SourceFile): Unit = ()
  def mainClass(sourceFile: SourceFile, className: String): Unit = ()
  def enabled: Boolean = false
  def usedName(className: String, name: String, useScopes: EnumSet[?]): Unit = ()
  def binaryDependency(onBinaryEntry: Path, onBinaryClassName: String, fromClassName: String,
      fromSourceFile: SourceFile, context: Any): Unit = ()
  def classDependency(onClassName: String, sourceClassName: String, context: Any): Unit = ()
  def generatedLocalClass(source: SourceFile, classFile: Path): Unit = ()
  def generatedNonLocalClass(source: SourceFile, classFile: Path, binaryClassName: String,
      srcClassName: String): Unit = ()
  def apiPhaseCompleted(): Unit = ()
  def dependencyPhaseCompleted(): Unit = ()
}
