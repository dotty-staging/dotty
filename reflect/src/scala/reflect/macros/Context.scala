package scala.reflect.macros

import scala.quoted.Quotes

class TypecheckException(message: String) extends RuntimeException(message)
class ParseException(message: String) extends RuntimeException(message)

/** Compatibility facade for helpers being rewritten from Scala 2 macros to
 *  Scala 3 inline/quoted macros.
 *
 *  It intentionally does not make legacy `def macro` expansion work. Code using
 *  this facade must be called from a Scala 3 macro implementation that already
 *  has a `Quotes` value.
 */
trait Context:
  val quotes: Quotes
  val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe

  type Expr[+T] = scala.quoted.Expr[T]
  type Tree = quotes.reflect.Tree
  type Type = universe.Type
  type Symbol = universe.Symbol
  type Position = universe.Position
  type WeakTypeTag[T] = universe.WeakTypeTag[T]
  type TypeTag[T] = universe.TypeTag[T]

  def enclosingPosition: Position = universe.NoPosition

  def abort(pos: Position, msg: String): Nothing =
    import quotes.reflect.*
    report.errorAndAbort(msg)

  def error(pos: Position, msg: String): Unit =
    import quotes.reflect.*
    report.error(msg)

  def warning(pos: Position, msg: String): Unit =
    import quotes.reflect.*
    report.warning(msg)

  def info(pos: Position, msg: String, force: Boolean): Unit =
    import quotes.reflect.*
    if force then report.info(msg)

  def typecheck(tree: Tree): Tree = tree

  def inferImplicitValue(tpe: Type): Tree =
    import quotes.reflect.*
    report.errorAndAbort(s"inferImplicitValue is not implemented by scala-reflect_3 compatibility facade for $tpe")
