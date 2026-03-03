package dotty.tools.dotc
package plugins

import scala.language.unsafeNulls

import core.*
import Contexts.*
import Phases.*
import dotty.tools.io.*
import transform.MegaPhase.MiniPhase

import java.io.InputStream
import java.util.Properties

import scala.util.{Try, Success, Failure}
import scala.annotation.nowarn

trait PluginPhase extends MiniPhase {
  def runsBefore: Set[String] = Set.empty
}

sealed trait Plugin {
  def name: String
  def description: String
  def isResearch: Boolean = isInstanceOf[ResearchPlugin]
  val optionsHelp: Option[String] = None
}

trait StandardPlugin extends Plugin {
  @deprecatedOverriding("Method 'init' does not allow to access 'Context', use 'initialize' instead.", since = "Scala 3.5.0")
  @deprecated("Use 'initialize' instead.", since = "Scala 3.5.0")
  def init(options: List[String]): List[PluginPhase] = Nil

  @nowarn("cat=deprecation")
  def initialize(options: List[String])(using Context): List[PluginPhase] = init(options)
}

trait ResearchPlugin extends Plugin {
  def init(options: List[String], plan: List[List[Phase]])(using Context): List[List[Phase]]
}

object Plugin {
  private val PluginFile = "plugin.properties"

  type AnyClass = Class[?]

  def load(classname: String, loader: ClassLoader): Try[AnyClass] =
    Failure(new PluginLoadException(classname, s"Plugin loading is not supported on Scala.js"))

  /** Returns empty list - plugin loading not supported on Scala.js */
  def loadAllFrom(
    paths: List[List[Path]],
    dirs: List[Path],
    ignoring: List[String]): List[Try[Plugin]] = Nil

  def instantiate(clazz: AnyClass): Plugin =
    throw new UnsupportedOperationException("Plugin instantiation is not supported on Scala.js")
}

class PluginLoadException(val path: String, message: String, cause: Exception) extends Exception(message, cause) {
  def this(path: String, message: String) = this(path, message, null)
}

class MissingPluginException(path: String) extends PluginLoadException(path, s"No plugin in path $path") {
  def this(paths: List[Path]) = this(paths mkString File.pathSeparator)
}
