package dotty.tools.dotc
package transform

import ast.*, core._
import Flags._
import Contexts._
import Symbols._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.quoted._
import dotty.tools.dotc.inlines.Inlines
import dotty.tools.dotc.ast.TreeMapWithImplicits
import dotty.tools.dotc.core.DenotTransformers.SymTransformer
import dotty.tools.dotc.staging.StagingLevel
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.StdNames.{str, nme}
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Names.{Name, TermName}

import scala.collection.mutable.ListBuffer
import dotty.tools.dotc.transform.MegaPhase.MiniPhase

class ReplaceInlinedTraitSymbols extends MiniPhase:
  import tpd._

  override def phaseName: String = ReplaceInlinedTraitSymbols.name
  override def description: String = ReplaceInlinedTraitSymbols.description
  override def changesMembers: Boolean = true
  override def changesParents: Boolean = true
  override def runsAfter: Set[String] =  Set("desugarSpecializedTraits", "specializeInlineTraits")

  override def transformSelect(tree: Select)(using Context): Tree =
    val qualType = tree.qualifier.tpe.widenDealias
    if ctx.inlineTraitState.inlinedSymbolIsRegistered(tree.symbol, qualType) then
      val newSym = ctx.inlineTraitState.lookupInlinedSymbol(tree.symbol, qualType)
      assert(tree.symbol.isTerm)
      tree.withType(newSym.termRef)
    else
      tree
      
  override def runsAfterGroupsOf: Set[String] = Set("specializeInlineTraits")
object ReplaceInlinedTraitSymbols:
  val name: String = "replaceInlinedTraitSymbols"
  val description: String = "Replace symbols referring to inline trait members with resulting inlined member symbols"
