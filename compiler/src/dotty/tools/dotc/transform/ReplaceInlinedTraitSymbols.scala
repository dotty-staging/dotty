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


// WE don't use th child this type so we should probabyl be able to get away with only one symbol map.

class ReplaceInlinedTraitSymbols extends MacroTransform:  //, SymTransformer:
  import tpd._
  import ast.tpd.*

  override def phaseName: String = ReplaceInlinedTraitSymbols.name
  override def description: String = ReplaceInlinedTraitSymbols.description
  override def changesMembers: Boolean = true
  override def changesParents: Boolean = true

  private def symbolReplacer(using Context) =
    
    val typeMap = new DeepTypeMap {
      override def apply(tp: Type): Type = tp match { // Deals exclusively with inner clases  
        case TypeRef(prefix: Type, sym: Symbol) =>
          val prefixType = prefix.widenDealias
          if ctx.inlineTraitState.inlinedSymbolIsRegistered(sym, prefixType) then
            val newSym = ctx.inlineTraitState.lookupInlinedSymbol(sym, prefixType)
            TypeRef(prefixType, newSym)
          else
            mapOver(tp)
        case _ =>
          mapOver(tp)
      }
    }

    def treeMap(tree: Tree) = tree match {
      case sel: Select =>
        val qualType = sel.qualifier.tpe.widenDealias
        if ctx.inlineTraitState.inlinedSymbolIsRegistered(sel.symbol, qualType) then
          val newSym = ctx.inlineTraitState.lookupInlinedSymbol(sel.symbol, qualType)
          if (sel.symbol.isTerm)
              tree.withType(newSym.termRef) // This path seems good
          else
              tree.withType(newSym.typeRef) // Also deals with inner classes only
        else
          tree
      case tdef: TypeDef if tdef.symbol.isClass => // maybe this belongs in the original inliner? Not clear exaclty what it does; does fire but only delegates to typemap
        tdef.symbol.info = typeMap(tdef.symbol.info)
        tdef
      case tree =>
        tree
    }
    
    new TreeTypeMap(
      typeMap = typeMap,
      treeMap = treeMap)
      
      //  {
      //   override def transform(tree: Tree)(using Context): Tree = tree match {
      //     case cls @ tpd.TypeDef(_, impl: Template) =>
      //       if (ctx.inlineTraitState.inlinedSymbolIsRegistered(cls.denot.symbol, ctx.ownd)) then
      //         // go recursively over the body only
      //         val impl1 = cpy.Template(impl)(body = super.transform(impl.body))
      //         cpy.TypeDef(cls)(rhs = impl1)
      //       else
      //         // we can map over the whole thing
      //         super.transform(tree)
      //     case _ => super.transform(tree)
      //   } 
      // }
  end symbolReplacer

// Need to look at the receiver type when doing the replacement - only replace with members that match the receiver type.   
// I think same for both Types and Terms - if you refer to it via the child you can specialise it otherwise no.
  override def newTransformer(using Context): Transformer = new Transformer {
    override def transform(tree: Tree)(using Context): Tree = 
      val state = ctx.inlineTraitState
      symbolReplacer(tree)
  }

  override def run(using Context): Unit =
    try super.run
    catch case _: CompilationUnit.SuspendException => ()


object ReplaceInlinedTraitSymbols:
  val name: String = "replaceInlinedTraitSymbols"
  val description: String = "Replace symbols referring to inline trait members with resulting inlined member symbols"


// Try and break it with:
  // calls inside and outside, inheritance inside and outside, reference to types inside and outisde, everything.


      //   val newDefs = inContext(ctx.withOwner(cls.symbol)) {
      //     inlineTraitAncestors(cls).foldLeft((List.empty[Tree], impl.body)){
      //       case ((inlineDefs, childDefs), parent) =>
      //         val parentTraitInliner = InlineParentTrait(parent)
      //         val overriddenSymbols = clsOverriddenSyms ++ inlineDefs.flatMap(_.symbol.allOverriddenSymbols)
      //         val inlinedDefs1 = inlineDefs ::: parentTraitInliner.expandDefs(overriddenSymbols)
      //         (inlinedDefs1, childDefs)
      //     }
      //   }
      //   val impl1 = cpy.Template(impl)(body = newDefs._1 ::: newDefs._2)
      //   cpy.TypeDef(cls)(rhs = impl1)


// claim we do this at the select level and hope that someone already added selects everywhere where we need them including internal to a class /// 
    // override def transformIdent(Ident)(using Context): Tree =
    //   if ctx.owner
    //   case ident: Ident if ctx.inlineTraitState.inlinedSymbolIsRegistered(ident.symbol) =>
    //     Ident(ctx.inlineTraitState.lookupInlinedSymbol(ident.symbol).namedType)
      
  // def adaptDefs(definitions: List[Tree]): List[Tree] = definitions.mapconserve(defsAdapter(_))


    // override def transformSelect(tree: Select)(using Context): Tree =


//   override def transformSym(symd: SymDenotation)(using Context): SymDenotation =
//     if symd.isClass && symd.owner.isInlineTrait && !symd.is(Module) then
//       symd.copySymDenotation(name = SpecializeInlineTraits.newInnerClassName(symd.name), initFlags = (symd.flags &~ Final) | Trait)
//     else
//       symd

//   override def checkPostCondition(tree: Tree)(using Context): Unit =
//     tree match {
//       // TODO check that things are inlined properly
//       case _ =>
//     }

//   private def transformInlineTrait(inlineTrait: TypeDef)(using Context): TypeDef =
//     val tpd.TypeDef(_, tmpl: Template) = inlineTrait: @unchecked
//     val body1 = tmpl.body.flatMap {
//       case innerClass: TypeDef if innerClass.symbol.isClass =>
//         val newTrait = makeTraitFromInnerClass(innerClass)
//         val newType = makeTypeFromInnerClass(inlineTrait.symbol, innerClass, newTrait.symbol)
//         List(newTrait, newType)
//       case member: MemberDef =>
//         List(member)
//       case _ =>
//         // Remove non-memberdefs, as they are normally placed into $init()
//         Nil
//     }
//     val tmpl1 = cpy.Template(tmpl)(body = body1)
//     cpy.TypeDef(inlineTrait)(rhs = tmpl1)
//   end transformInlineTrait

//   private def makeTraitFromInnerClass(innerClass: TypeDef)(using Context): TypeDef =
//     val TypeDef(name, tmpl: Template) = innerClass: @unchecked
//     val newInnerParents = tmpl.parents.mapConserve(ConcreteParentStripper.apply)
//     val tmpl1 = cpy.Template(tmpl)(parents = newInnerParents) // TODO .withType(???)
//     val newTrait = cpy.TypeDef(innerClass)(name = SpecializeInlineTraits.newInnerClassName(name), rhs = tmpl1)
//     newTrait.symbol.setFlag(Synthetic)
//     newTrait
//   end makeTraitFromInnerClass

//   private def makeTypeFromInnerClass(parentSym: Symbol, innerClass: TypeDef, newTraitSym: Symbol)(using Context): TypeDef =
//     val upperBound = innerClass.symbol.primaryConstructor.info match {
//       case _: MethodType =>
//         newTraitSym.typeRef
//       case poly: PolyType =>
//         HKTypeLambda(poly.paramNames)(tl => poly.paramInfos, tl => newTraitSym.typeRef.appliedTo(tl.paramRefs.head))
//     }
//     val newTypeSym = newSymbol(
//       owner = parentSym,
//       name = newTraitSym.name.asTypeName,
//       flags = innerClass.symbol.flags & (Private | Protected) | Synthetic,
//       info = TypeBounds.upper(upperBound),
//       privateWithin = innerClass.symbol.privateWithin,
//       coord = innerClass.symbol.coord,
//       nestingLevel = innerClass.symbol.nestingLevel,
//     ).asType
//     TypeDef(newTypeSym)
//   end makeTypeFromInnerClass

//   private object ConcreteParentStripper extends TreeAccumulator[Tree] {
//     def apply(tree: Tree)(using Context): Tree = apply(tree, tree)

//     override def apply(x: Tree, tree: Tree)(using Context): Tree = tree match {
//       case ident: Ident => ident
//       case tpt: TypeTree => tpt
//       case _ => foldOver(x, tree)
//     }
//   }
// }


  
  //   val sym = tree.symbol
  //   val qualTypeSym = tree.qualifier.tpe.widenDealias.typeSymbol
  //   if !(sym.isTerm && sym.owner.isClass) || sym.maybeOwner.eq(qualTypeSym) || !qualTypeSym.isClass then tree
  //   else
  //     val devirtualizedSym = sym.overriddenSymbol(qualTypeSym.asClass)
  //     if !devirtualizedSym.exists || sym.eq(devirtualizedSym) || devirtualizedSym.isAllOf(Mutable | JavaDefined) then tree
  //     else tree.withType(devirtualizedSym.termRef)
