package dotty.tools.dotc
package sbt

import core.*
import Contexts.*
import Phases.*

/** Stub ExtractAPI for Scala.js — sbt incremental compilation callbacks not supported. */
class ExtractAPI extends Phase {
  override def phaseName: String = ExtractAPI.name
  override def description: String = ExtractAPI.description
  override def isRunnable(using Context): Boolean = false
  override def isCheckable: Boolean = false

  override def run(using Context): Unit = ()
}

object ExtractAPI {
  val name: String = "sbt-api"
  val description: String = "sends a representation of the API of classes to sbt"
  import dotty.tools.dotc.util.Property
  val NonLocalClassSymbolsInCurrentUnits: Property.Key[scala.collection.mutable.HashSet[Symbol]] = Property.Key()
}
