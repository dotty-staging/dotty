package scala.reflect.runtime

import scala.quoted.*

private[reflect] object CompatTagMacros:
  def typeTagImpl[T: Type](using Quotes): Expr[universe.TypeTag[T]] =
    val repr = Type.show[T]
    '{ scala.reflect.runtime.universe.TypeTag.fromString[T](scala.reflect.runtime.currentMirror, ${ Expr(repr) }) }

  def weakTypeTagImpl[T: Type](using Quotes): Expr[universe.WeakTypeTag[T]] =
    val repr = Type.show[T]
    '{ scala.reflect.runtime.universe.WeakTypeTag.fromString[T](scala.reflect.runtime.currentMirror, ${ Expr(repr) }) }
