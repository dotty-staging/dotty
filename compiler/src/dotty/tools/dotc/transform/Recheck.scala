package dotty.tools
package dotc
package transform

import core.*
import Symbols.*, Contexts.*, Types.*, ContextOps.*, Decorators.*, SymDenotations.*
import Flags.*
import Phases.Phase
import DenotTransformers.IdentityDenotTransformer
import ast.*
import typer.ProtoTypes.*
import config.Printers.recheckr

class Recheck extends Phase, IdentityDenotTransformer:
  thisPhase =>

  import ast.tpd.*

  def preRecheckPhase = this.prev.asInstanceOf[PreRecheck]

  def run(using Context): Unit =
    val refiner = newRechecker()
    val unit = ctx.compilationUnit
    recheckr.println(i"recheck types of $unit")
    newRechecker().check()

  def newRechecker()(using Context): Rechecker = Rechecker(ctx)

  class Rechecker(ictx: Context):
    val ta = ictx.typeAssigner

    extension (sym: Symbol) def updateInfo(newInfo: Type)(using Context): Unit =
      sym.copySymDenotation().installAfter(thisPhase) // reset
      sym.copySymDenotation(
          info = newInfo,
          initFlags =
            if newInfo.isInstanceOf[LazyType] then sym.flags &~ Touched
            else sym.flags
        ).installAfter(preRecheckPhase)

    def recheckIdent(tree: Ident)(using Context): Type =
      ta.ensureAccessible(tree.symbol.namedType, superAccess = false, tree.srcPos)

    def recheckSelect(tree: Select, pt: Type)(using Context): Type = tree match
      case Select(qual, name) =>
        val superAccess = qual.isInstanceOf[Super]
        val qualType = recheck(qual, WildcardType)
        val pre = ta.maybeSkolemizePrefix(qualType, name)
        val mbr = qualType.findMember(name, pre)
        assert(ta.reallyExists(mbr))
        val rawType = qualType.select(name, mbr)
        ta.accessibleType(rawType, superAccess)

    def recheckBind(tree: Bind, pt: Type)(using Context): Type = tree match
      case Bind(name, body) =>
        val bodyType = recheck(body, pt)
        tree.symbol.updateInfo(pt & bodyType)
        tree.tpe

    def recheckLabeled(tree: Labeled)(using Context): Type = tree match
      case Labeled(bind, expr) =>
        recheck(bind, WildcardType)
        val info = bind.symbol.info
        recheck(expr, info)
        info

    def recheckValDef(tree: ValDef, sym: Symbol)(using Context): Type = ???
    def recheckDefDef(tree: DefDef, sym: Symbol)(using Context): Type = ???
    def recheckTypeDef(tree: TypeDef, sym: Symbol)(using Context): Type = ???
    def recheckClassDef(tree: TypeDef, sym: ClassSymbol)(using Context): Type = ???

    def recheckApply(tree: Apply, pt: Type)(using Context): Type = ???
    def recheckThis(tree: This)(using Context): Type = ???
    def recheckTypeApply(tree: TypeApply, pt: Type)(using Context): Type = ???
    def recheckLiteral(tree: Literal)(using Context): Type = ???
    def recheckNew(tree: New, pt: Type)(using Context): Type = ???
    def recheckTyped(tree: Typed, pt: Type)(using Context): Type = ???
    def recheckAssign(tree: Assign, pt: Type)(using Context): Type = ???
    def recheckBlock(tree: Block, pt: Type)(using Context): Type = ???
    def recheckIf(tree: If, pt: Type)(using Context): Type = ???
    def recheckClosure(tree: Closure, pt: Type)(using Context): Type = ???
    def recheckMatch(tree: Match, pt: Type)(using Context): Type = ???
    def recheckReturn(tree: Return)(using Context): Type = ???
    def recheckWhileDo(tree: WhileDo)(using Context): Type = ???
    def recheckTry(tree: Try, pt: Type)(using Context): Type = ???
    def recheckSuper(tree: Super, pt: Type)(using Context): Type = ???
    def recheckSeqLiteral(tree: SeqLiteral, pt: Type)(using Context): Type = ???
    def recheckInlined(tree: Inlined, pt: Type)(using Context): Type = ???
    def recheckTypeTree(tree: TypeTree, pt: Type)(using Context): Type = ???
    def recheckPackageDef(tree: PackageDef)(using Context): Type = ???

    /** Typecheck tree without adapting it, returning a recheck tree.
     *  @param initTree    the unrecheck tree
     *  @param pt          the expected result type
     *  @param locked      the set of type variables of the current typer state that cannot be interpolated
     *                     at the present time
     */
    def recheck(tree: Tree, pt: Type)(using Context): Type =

      def recheckNamed(tree: NameTree, pt: Type)(using Context): Type =
        val sym = tree.symbol
        tree match
          case tree: Ident => recheckIdent(tree)
          case tree: Select => recheckSelect(tree, pt)
          case tree: Bind => recheckBind(tree, pt)
          case tree: ValDef =>
            if tree.isEmpty then NoType
            else recheckValDef(tree, sym)(using ctx.localContext(tree, sym))
          case tree: DefDef =>
            recheckDefDef(tree, sym)(using ctx.localContext(tree, sym))
          case tree: TypeDef =>
            if tree.isClassDef then
              recheckClassDef(tree, sym.asClass)(using ctx.localContext(tree, sym))
            else
              recheckTypeDef(tree, sym)(using ctx.localContext(tree, sym).setNewScope)
          case tree: Labeled => recheckLabeled(tree)

      def recheckUnnamed(tree: Tree, pt: Type): Type = tree match
        case tree: Apply => recheckApply(tree, pt)
        case tree: This => recheckThis(tree)
        case tree: TypeApply => recheckTypeApply(tree, pt)
        case tree: Literal => recheckLiteral(tree)
        case tree: New => recheckNew(tree, pt)
        case tree: Typed => recheckTyped(tree, pt)
        case tree: Assign => recheckAssign(tree, pt)
        case tree: Block => recheckBlock(tree, pt)(using ctx.fresh.setNewScope)
        case tree: If => recheckIf(tree, pt)
        case tree: Closure => recheckClosure(tree, pt)
        case tree: Match => recheckMatch(tree, pt)
        case tree: Return => recheckReturn(tree)
        case tree: WhileDo => recheckWhileDo(tree)
        case tree: Try => recheckTry(tree, pt)
        case tree: Super => recheckSuper(tree, pt)
        case tree: SeqLiteral => recheckSeqLiteral(tree, pt)
        case tree: Inlined => recheckInlined(tree, pt)
        case tree: TypeTree => recheckTypeTree(tree, pt)
        case tree: PackageDef => recheckPackageDef(tree)

      try
        val result = tree match
          case tree: NameTree => recheckNamed(tree, pt)
          case tree => recheckUnnamed(tree, pt)
        checkConforms(result, pt, tree)
        result
      catch case ex: Exception =>
        println(i"error while rechecking $tree")
        throw ex
    end recheck

    def checkConforms(tpe: Type, pt: Type, tree: Tree)(using Context): Unit = tree match
      case tree: DefTree =>
      case _ => ???

    def check()(using Context): Unit =
      val unit = ictx.compilationUnit
      recheck(unit.tpdTree, WildcardType)

  end Rechecker
end Recheck
