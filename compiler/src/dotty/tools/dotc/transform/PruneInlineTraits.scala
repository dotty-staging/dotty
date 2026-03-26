package dotty.tools.dotc
package transform

import core._
import Contexts._
import DenotTransformers.SymTransformer
import Flags._
import SymDenotations._
import Symbols._
import MegaPhase.MiniPhase
import ast.tpd
import dotty.tools.dotc.core.StdNames.str

class PruneInlineTraits extends MiniPhase with SymTransformer { thisTransform =>
  import tpd._
  import PruneInlineTraits._

  override def phaseName: String = PruneInlineTraits.name

  override def description: String = PruneInlineTraits.description

  override def transformSym(sym: SymDenotation)(using Context): SymDenotation =
    if isDeletable(sym) then sym.copySymDenotation(initFlags = (sym.flags ^ Private) | Deferred | Protected, name = sym.name ++ str.INLINE_TRAIT_ERASED_PRIVATE_SUFFIX)
    else if isEraseable(sym) then sym.copySymDenotation(initFlags = sym.flags | Deferred)
    else if sym.isInlineTrait then sym.copySymDenotation(initFlags = sym.flags | PureInterface | NoInits)
    else sym
    
  override def transformValDef(tree: ValDef)(using Context): Tree = 
    if isDeletable(tree.symbol) || isEraseable(tree.symbol) then cpy.ValDef(tree)(rhs = EmptyTree)
    else tree

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    if isDeletable(tree.symbol) || isEraseable(tree.symbol) then cpy.DefDef(tree)(rhs = EmptyTree)
    else tree

  private def isEraseable(sym: SymDenotation)(using Context): Boolean =
    !sym.isType
    && !sym.isConstructor
    && !sym.is(Param)
    && !sym.is(ParamAccessor)
    && !sym.is(Private)
    && !sym.isLocalDummy
    && sym.owner.isInlineTrait

  // We also must erase private symbols because they can contain problematic defintions such 
  // as inline functions which need to be inlined (see tests/pos/inline-trait-private-nested-inline-must-delete.scala)
  // It's hard to delete the actual symbol and we can't leave it private and deferred/with no definition
  // Thus we settle for making it protected, deferred (no definition) and giving it a mangled name/
  private def isDeletable(sym: SymDenotation)(using Context): Boolean = 
    !sym.isType
    && sym.is(Private)
    && sym.owner.isInlineTrait
    && !sym.is(Param)
    && !sym.is(ParamAccessor)
}

object PruneInlineTraits {
  import tpd._

  val name: String = "pruneInlineTraits"
  val description: String = "drop rhs definitions in inline traits"
}
