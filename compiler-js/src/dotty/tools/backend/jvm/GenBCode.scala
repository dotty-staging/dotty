package dotty.tools.backend.jvm

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.*
import Contexts.*

/** Stub GenBCode phase for Scala.js - the JVM backend is not used */
class GenBCode extends Phase {
  override def phaseName: String = GenBCode.name
  override def description: String = GenBCode.description
  override def isRunnable(using Context): Boolean = false

  protected def run(using Context): Unit = ()

  def registerEntryPoint(s: String): Unit = () // No-op on JS
}

object GenBCode {
  val name: String = "genBCode"
  val description: String = "generate JVM bytecode"

  val CLASS_CONSTRUCTOR_NAME = "<clinit>"
  val INSTANCE_CONSTRUCTOR_NAME = "<init>"

}

object BackendUtils {
  lazy val classfileVersionMap: Map[Int, Int] = (8 to 26).map(v => v -> (44 + v)).toMap
}
