package dotty.tools.dotc
package sbt

import xsbti.api.*

import scala.util.Try

/** Stub ShowAPI for Scala.js - sbt integration not needed for MVP */
object DefaultShowAPI {
  def apply(d: Definition): String = ""
  def apply(d: Type): String = ""
  def apply(a: ClassLike): String = ""
}

object ShowAPI {
  def showApi(c: ClassLike)(implicit nesting: Int): String = ""
  def showDefinition(d: Definition)(implicit nesting: Int): String = ""
  def showType(t: Type)(implicit nesting: Int): String = ""
}
