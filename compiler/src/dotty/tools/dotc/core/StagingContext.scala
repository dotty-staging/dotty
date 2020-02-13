package dotty.tools.dotc.core

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.util.Property

import scala.collection.mutable

object StagingContext {

  /** A key to be used in a context property that tracks the quoteation level */
  private val QuotationLevel = new Property.Key[Int]

  private val TaggedTypes = new Property.Key[mutable.LinkedHashMap[Symbol, TermRef]]

  /** All enclosing calls that are currently inlined, from innermost to outermost. */
  def level(implicit ctx: Context): Int =
    ctx.property(QuotationLevel).getOrElse(0)

  /** Context with an incremented quotation level. */
  def quoteContext(implicit ctx: Context): Context =
    ctx.fresh.setProperty(QuotationLevel, level + 1)

  /** Context with a decremented quotation level. */
  def spliceContext(implicit ctx: Context): Context =
    ctx.fresh.setProperty(QuotationLevel, level - 1)

  def contextTaggedTypes(taggedTypes: mutable.LinkedHashMap[Symbol, TermRef])(implicit ctx: Context) =
    ctx.fresh.setProperty(TaggedTypes, taggedTypes)

  def getTaggedTypes(implicit ctx: Context): mutable.LinkedHashMap[Symbol, TermRef] =
    ctx.property(TaggedTypes).get

}

