package dotty.tools
package dotc
package transform

import scala.compiletime.uninitialized

import core.*
import Contexts.*, Phases.*, Symbols.*, Decorators.*
import Flags.PackageVal
import staging.StagingLevel.*

/** A MegaPhase combines a number of mini-phases which are all executed in
 *  a single tree traversal.
 *
 *  This Scala.js version replaces the java.lang.reflect.Method-based `defines` method
 *  with a conservative approach that assumes all mini-phases define all methods.
 */
object MegaPhase {
  import ast.tpd.*

  abstract class MiniPhase extends Phase {

    private[MegaPhase] var superPhase: MegaPhase = uninitialized
    private[MegaPhase] var idxInGroup: Int = uninitialized

    def runsAfterGroupsOf: Set[String] = Set.empty

    final override def relaxedTyping: Boolean = superPhase.relaxedTyping

    def relaxedTypingInGroup: Boolean = false

    protected def mkTreeTransformer: TreeTransform = new TreeTransform(this)

    def prepareForIdent(tree: Ident)(using Context): Context = ctx
    def prepareForSelect(tree: Select)(using Context): Context = ctx
    def prepareForThis(tree: This)(using Context): Context = ctx
    def prepareForSuper(tree: Super)(using Context): Context = ctx
    def prepareForApply(tree: Apply)(using Context): Context = ctx
    def prepareForTypeApply(tree: TypeApply)(using Context): Context = ctx
    def prepareForLiteral(tree: Literal)(using Context): Context = ctx
    def prepareForNew(tree: New)(using Context): Context = ctx
    def prepareForTyped(tree: Typed)(using Context): Context = ctx
    def prepareForAssign(tree: Assign)(using Context): Context = ctx
    def prepareForBlock(tree: Block)(using Context): Context = ctx
    def prepareForIf(tree: If)(using Context): Context = ctx
    def prepareForClosure(tree: Closure)(using Context): Context = ctx
    def prepareForMatch(tree: Match)(using Context): Context = ctx
    def prepareForCaseDef(tree: CaseDef)(using Context): Context = ctx
    def prepareForLabeled(tree: Labeled)(using Context): Context = ctx
    def prepareForReturn(tree: Return)(using Context): Context = ctx
    def prepareForWhileDo(tree: WhileDo)(using Context): Context = ctx
    def prepareForTry(tree: Try)(using Context): Context = ctx
    def prepareForSeqLiteral(tree: SeqLiteral)(using Context): Context = ctx
    def prepareForInlined(tree: Inlined)(using Context): Context = ctx
    def prepareForTypeTree(tree: TypeTree)(using Context): Context = ctx
    def prepareForBind(tree: Bind)(using Context): Context = ctx
    def prepareForAlternative(tree: Alternative)(using Context): Context = ctx
    def prepareForUnApply(tree: UnApply)(using Context): Context = ctx
    def prepareForValDef(tree: ValDef)(using Context): Context = ctx
    def prepareForDefDef(tree: DefDef)(using Context): Context = ctx
    def prepareForTypeDef(tree: TypeDef)(using Context): Context = ctx
    def prepareForTemplate(tree: Template)(using Context): Context = ctx
    def prepareForPackageDef(tree: PackageDef)(using Context): Context = ctx
    def prepareForStats(trees: List[Tree])(using Context): Context = ctx
    def prepareForUnit(tree: Tree)(using Context): Context = ctx
    def prepareForOther(tree: Tree)(using Context): Context = ctx

    def transformIdent(tree: Ident)(using Context): Tree = tree
    def transformSelect(tree: Select)(using Context): Tree = tree
    def transformThis(tree: This)(using Context): Tree = tree
    def transformSuper(tree: Super)(using Context): Tree = tree
    def transformApply(tree: Apply)(using Context): Tree = tree
    def transformTypeApply(tree: TypeApply)(using Context): Tree = tree
    def transformLiteral(tree: Literal)(using Context): Tree = tree
    def transformNew(tree: New)(using Context): Tree = tree
    def transformTyped(tree: Typed)(using Context): Tree = tree
    def transformAssign(tree: Assign)(using Context): Tree = tree
    def transformBlock(tree: Block)(using Context): Tree = tree
    def transformIf(tree: If)(using Context): Tree = tree
    def transformClosure(tree: Closure)(using Context): Tree = tree
    def transformMatch(tree: Match)(using Context): Tree = tree
    def transformCaseDef(tree: CaseDef)(using Context): Tree = tree
    def transformLabeled(tree: Labeled)(using Context): Tree = tree
    def transformReturn(tree: Return)(using Context): Tree = tree
    def transformWhileDo(tree: WhileDo)(using Context): Tree = tree
    def transformTry(tree: Try)(using Context): Tree = tree
    def transformSeqLiteral(tree: SeqLiteral)(using Context): Tree = tree
    def transformInlined(tree: Inlined)(using Context): Tree = tree
    def transformTypeTree(tree: TypeTree)(using Context): Tree = tree
    def transformBind(tree: Bind)(using Context): Tree = tree
    def transformAlternative(tree: Alternative)(using Context): Tree = tree
    def transformUnApply(tree: UnApply)(using Context): Tree = tree
    def transformValDef(tree: ValDef)(using Context): Tree = tree
    def transformDefDef(tree: DefDef)(using Context): Tree = tree
    def transformTypeDef(tree: TypeDef)(using Context): Tree = tree
    def transformTemplate(tree: Template)(using Context): Tree = tree
    def transformPackageDef(tree: PackageDef)(using Context): Tree = tree
    def transformStats(trees: List[Tree])(using Context): List[Tree] = trees
    def transformUnit(tree: Tree)(using Context): Tree = tree
    def transformOther(tree: Tree)(using Context): Tree = tree

    /** Transform tree using all transforms of current group (including this one) */
    def transformAllDeep(tree: Tree)(using Context): Tree =
      superPhase.transformTree(tree, 0)

    /** Transform tree using all transforms following the current one in this group */
    def transformFollowingDeep(tree: Tree)(using Context): Tree =
      superPhase.transformTree(tree, idxInGroup + 1)

    /** Transform single node using all transforms following the current one in this group */
    def transformFollowing(tree: Tree)(using Context): Tree =
      superPhase.transformNode(tree, idxInGroup + 1)

    protected def singletonGroup: MegaPhase = new MegaPhase(Array(this))

    protected def run(using Context): Unit =
      singletonGroup.run

    override def isRunnable(using Context): Boolean = super.isRunnable && !ctx.usedBestEffortTasty

    /** Is this mini-phase enabled for tree based on the current configuration? */
    override def isEnabled(using Context): Boolean = true

    def initContext(using Context): Unit = ()
  }

  /** A sentinel class for empty phases in the nx array */
  private val NoMiniPhase: MiniPhase | Null = null

  class TreeTransform(val miniPhase: MiniPhase) {
    def phase: MiniPhase = miniPhase
  }
}

import MegaPhase.*

class MegaPhase(val miniPhases: Array[MiniPhase]) extends Phase {
  override val phaseName: String =
    if (miniPhases.length == 1) miniPhases(0).phaseName
    else miniPhases.map(_.phaseName).mkString("MegaPhase{", ", ", "}")

  override def description: String =
    if (miniPhases.length == 1) miniPhases(0).description
    else miniPhases.map(_.description).mkString("MegaPhase{", ", ", "}")

  /** Used in progress reporting to avoid super long phase names */
  lazy val shortPhaseName: String =
    if (miniPhases.length == 1) miniPhases(0).phaseName
    else
      s"MegaPhase{${miniPhases.head.phaseName},...,${miniPhases.last.phaseName}}"

  private var relaxedTypingCache: Boolean = false
  private var relaxedTypingKnown: Boolean = false
  override def relaxedTyping: Boolean = {
    if (!relaxedTypingKnown) {
      relaxedTypingCache = miniPhases.exists(_.relaxedTypingInGroup)
      relaxedTypingKnown = true
    }
    relaxedTypingCache
  }

  override def runsAfter: Set[String] = miniPhases.head.runsAfter

  override val changesMembers: Boolean = miniPhases.exists(_.changesMembers)
  override val changesParents: Boolean = miniPhases.exists(_.changesParents)
  override val changesBaseTypes: Boolean = miniPhases.exists(_.changesBaseTypes)

  def initContext(using Context): Unit =
    miniPhases.foreach(_.initContext)

  private val cpy: ast.tpd.TreeCopier = ast.tpd.cpy

  import ast.tpd.*

  def transformNode(tree: Tree, start: Int)(using Context): Tree = {
    def goNamed(tree: Tree, start: Int) =
      try
        tree match {
          case tree: Ident => goIdent(tree, start)
          case tree: Select => goSelect(tree, start)
          case tree: ValDef => goValDef(tree, start)
          case tree: DefDef => goDefDef(tree, start)
          case tree: TypeDef => goTypeDef(tree, start)
          case tree: Labeled => goLabeled(tree, start)
          case tree: Bind => goBind(tree, start)
          case _ => goOther(tree, start)
        }
      catch {
        case ex: TypeError =>
          report.error(ex, tree.srcPos)
          tree
      }
    def goUnnamed(tree: Tree, start: Int) =
      try
        tree match {
          case tree: Apply => goApply(tree, start)
          case tree: TypeTree => goTypeTree(tree, start)
          case tree: Thicket =>
            cpy.Thicket(tree)(tree.trees.mapConserve(transformNode(_, start)))
          case tree: This => goThis(tree, start)
          case tree: Literal => goLiteral(tree, start)
          case tree: Block => goBlock(tree, start)
          case tree: TypeApply => goTypeApply(tree, start)
          case tree: If => goIf(tree, start)
          case tree: New => goNew(tree, start)
          case tree: Typed => goTyped(tree, start)
          case tree: CaseDef => goCaseDef(tree, start)
          case tree: Closure => goClosure(tree, start)
          case tree: Assign => goAssign(tree, start)
          case tree: SeqLiteral => goSeqLiteral(tree, start)
          case tree: Super => goSuper(tree, start)
          case tree: Template => goTemplate(tree, start)
          case tree: Match => goMatch(tree, start)
          case tree: UnApply => goUnApply(tree, start)
          case tree: Try => goTry(tree, start)
          case tree: Inlined => goInlined(tree, start)
          case tree: Return => goReturn(tree, start)
          case tree: Alternative => goAlternative(tree, start)
          case tree: WhileDo => goWhileDo(tree, start)
          case tree: PackageDef => goPackageDef(tree, start)
          case _ => goOther(tree, start)
        }
      catch {
        case ex: TypeError =>
          report.error(ex, tree.srcPos)
          tree
      }
    if (tree.isInstanceOf[NameTree]) goNamed(tree, start)
    else goUnnamed(tree, start)
  }

  def transformTree(tree: Tree, start: Int)(using Context): Tree = {
    tree match {
      case tree: Ident => goIdent(tree, start)
      case tree: Select => goSelect(tree, start)
      case tree: ValDef if !tree.isEmpty => goValDef(tree, start)
      case tree: DefDef => goDefDef(tree, start)
      case tree: TypeDef => goTypeDef(tree, start)
      case tree: Bind => goBind(tree, start)
      case tree: This => goThis(tree, start)
      case tree: Super => goSuper(tree, start)
      case tree: Apply => goApply(tree, start)
      case tree: TypeApply => goTypeApply(tree, start)
      case tree: Literal => goLiteral(tree, start)
      case tree: New => goNew(tree, start)
      case tree: Typed => goTyped(tree, start)
      case tree: Assign => goAssign(tree, start)
      case tree: Block => goBlock(tree, start)
      case tree: If => goIf(tree, start)
      case tree: Closure => goClosure(tree, start)
      case tree: Match => goMatch(tree, start)
      case tree: Labeled => goLabeled(tree, start)
      case tree: Return => goReturn(tree, start)
      case tree: WhileDo => goWhileDo(tree, start)
      case tree: Try => goTry(tree, start)
      case tree: SeqLiteral => goSeqLiteral(tree, start)
      case tree: Inlined => goInlined(tree, start)
      case tree: TypeTree => goTypeTree(tree, start)
      case tree: Alternative => goAlternative(tree, start)
      case tree: UnApply => goUnApply(tree, start)
      case tree: Template => goTemplate(tree, start)
      case tree: PackageDef => goPackageDef(tree, start)
      case tree: Thicket =>
        cpy.Thicket(tree)(tree.trees.mapConserve(transformTree(_, start)))
      case _ =>
        goOther(tree, start)
    }
  }

  // Simple dispatch methods - for the JS version, all mini-phases are always called
  // (conservative approach instead of reflection-based `defines` check)

  private def goIdent(tree: Ident, start: Int)(using Context): Tree = {
    var cur = tree: Tree
    var idx = start
    while (idx < miniPhases.length) {
      val phase = miniPhases(idx)
      cur = phase.transformIdent(cur.asInstanceOf[Ident])(using prepIdent(tree, idx))
      idx += 1
    }
    cur
  }

  private def goSelect(tree: Select, start: Int)(using Context): Tree = {
    val qual = transformTree(tree.qualifier, start)
    var cur: Tree = cpy.Select(tree)(qual, tree.name)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformSelect(cur.asInstanceOf[Select])
      idx += 1
    }
    cur
  }

  private def goThis(tree: This, start: Int)(using Context): Tree = {
    var cur = tree: Tree
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformThis(cur.asInstanceOf[This])
      idx += 1
    }
    cur
  }

  private def goSuper(tree: Super, start: Int)(using Context): Tree = {
    val qual = transformTree(tree.qual, start)
    var cur: Tree = cpy.Super(tree)(qual, tree.mix)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformSuper(cur.asInstanceOf[Super])
      idx += 1
    }
    cur
  }

  private def goApply(tree: Apply, start: Int)(using Context): Tree = {
    val fun = transformTree(tree.fun, start)
    val args = transformTrees(tree.args, start)
    var cur: Tree = cpy.Apply(tree)(fun, args)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformApply(cur.asInstanceOf[Apply])
      idx += 1
    }
    cur
  }

  private def goTypeApply(tree: TypeApply, start: Int)(using Context): Tree = {
    val fun = transformTree(tree.fun, start)
    val args = transformTrees(tree.args, start)
    var cur: Tree = cpy.TypeApply(tree)(fun, args)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTypeApply(cur.asInstanceOf[TypeApply])
      idx += 1
    }
    cur
  }

  private def goLiteral(tree: Literal, start: Int)(using Context): Tree = {
    var cur = tree: Tree
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformLiteral(cur.asInstanceOf[Literal])
      idx += 1
    }
    cur
  }

  private def goNew(tree: New, start: Int)(using Context): Tree = {
    val tpt = transformTree(tree.tpt, start)
    var cur: Tree = cpy.New(tree)(tpt)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformNew(cur.asInstanceOf[New])
      idx += 1
    }
    cur
  }

  private def goTyped(tree: Typed, start: Int)(using Context): Tree = {
    val expr = transformTree(tree.expr, start)
    val tpt = transformTree(tree.tpt, start)
    var cur: Tree = cpy.Typed(tree)(expr, tpt)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTyped(cur.asInstanceOf[Typed])
      idx += 1
    }
    cur
  }

  private def goAssign(tree: Assign, start: Int)(using Context): Tree = {
    val lhs = transformTree(tree.lhs, start)
    val rhs = transformTree(tree.rhs, start)
    var cur: Tree = cpy.Assign(tree)(lhs, rhs)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformAssign(cur.asInstanceOf[Assign])
      idx += 1
    }
    cur
  }

  private def goBlock(tree: Block, start: Int)(using Context): Tree = {
    val stats = transformStats(tree.stats, tree.expr, start)
    val expr = transformTree(tree.expr, start)
    var cur: Tree = cpy.Block(tree)(stats, expr)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformBlock(cur.asInstanceOf[Block])
      idx += 1
    }
    cur
  }

  private def goIf(tree: If, start: Int)(using Context): Tree = {
    val cond = transformTree(tree.cond, start)
    val thenp = transformTree(tree.thenp, start)
    val elsep = transformTree(tree.elsep, start)
    var cur: Tree = cpy.If(tree)(cond, thenp, elsep)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformIf(cur.asInstanceOf[If])
      idx += 1
    }
    cur
  }

  private def goClosure(tree: Closure, start: Int)(using Context): Tree = {
    val env = transformTrees(tree.env, start)
    val meth = transformTree(tree.meth, start)
    val tpt = transformTree(tree.tpt, start)
    var cur: Tree = cpy.Closure(tree)(env, meth, tpt)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformClosure(cur.asInstanceOf[Closure])
      idx += 1
    }
    cur
  }

  private def goMatch(tree: Match, start: Int)(using Context): Tree = {
    val selector = transformTree(tree.selector, start)
    val cases = transformSpecificTrees(tree.cases, start)
    var cur: Tree = cpy.Match(tree)(selector, cases)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformMatch(cur.asInstanceOf[Match])
      idx += 1
    }
    cur
  }

  private def goLabeled(tree: Labeled, start: Int)(using Context): Tree = {
    val bind = transformTree(tree.bind, start)
    val expr = transformTree(tree.expr, start)
    var cur: Tree = cpy.Labeled(tree)(bind.asInstanceOf[Bind], expr)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformLabeled(cur.asInstanceOf[Labeled])
      idx += 1
    }
    cur
  }

  private def goReturn(tree: Return, start: Int)(using Context): Tree = {
    val expr = transformTree(tree.expr, start)
    var cur: Tree = cpy.Return(tree)(expr, tree.from)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformReturn(cur.asInstanceOf[Return])
      idx += 1
    }
    cur
  }

  private def goWhileDo(tree: WhileDo, start: Int)(using Context): Tree = {
    val cond = transformTree(tree.cond, start)
    val body = transformTree(tree.body, start)
    var cur: Tree = cpy.WhileDo(tree)(cond, body)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformWhileDo(cur.asInstanceOf[WhileDo])
      idx += 1
    }
    cur
  }

  private def goTry(tree: Try, start: Int)(using Context): Tree = {
    val expr = transformTree(tree.expr, start)
    val cases = transformSpecificTrees(tree.cases, start)
    val finalizer = transformTree(tree.finalizer, start)
    var cur: Tree = cpy.Try(tree)(expr, cases, finalizer)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTry(cur.asInstanceOf[Try])
      idx += 1
    }
    cur
  }

  private def goSeqLiteral(tree: SeqLiteral, start: Int)(using Context): Tree = {
    val elems = transformTrees(tree.elems, start)
    val elemtpt = transformTree(tree.elemtpt, start)
    var cur: Tree = cpy.SeqLiteral(tree)(elems, elemtpt)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformSeqLiteral(cur.asInstanceOf[SeqLiteral])
      idx += 1
    }
    cur
  }

  private def goInlined(tree: Inlined, start: Int)(using Context): Tree = {
    val bindings = transformSpecificTrees(tree.bindings, start)
    val expansion = transformTree(tree.expansion, start)
    var cur: Tree = cpy.Inlined(tree)(tree.call, bindings, expansion)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformInlined(cur.asInstanceOf[Inlined])
      idx += 1
    }
    cur
  }

  private def goTypeTree(tree: TypeTree, start: Int)(using Context): Tree = {
    var cur = tree: Tree
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTypeTree(cur.asInstanceOf[TypeTree])
      idx += 1
    }
    cur
  }

  private def goBind(tree: Bind, start: Int)(using Context): Tree = {
    val body = transformTree(tree.body, start)
    var cur: Tree = cpy.Bind(tree)(tree.name, body)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformBind(cur.asInstanceOf[Bind])
      idx += 1
    }
    cur
  }

  private def goAlternative(tree: Alternative, start: Int)(using Context): Tree = {
    val trees = transformTrees(tree.trees, start)
    var cur: Tree = cpy.Alternative(tree)(trees)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformAlternative(cur.asInstanceOf[Alternative])
      idx += 1
    }
    cur
  }

  private def goUnApply(tree: UnApply, start: Int)(using Context): Tree = {
    val fun = transformTree(tree.fun, start)
    val implicits = transformTrees(tree.implicits, start)
    val patterns = transformTrees(tree.patterns, start)
    var cur: Tree = cpy.UnApply(tree)(fun, implicits, patterns)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformUnApply(cur.asInstanceOf[UnApply])
      idx += 1
    }
    cur
  }

  private def goValDef(tree: ValDef, start: Int)(using Context): Tree = {
    val tpt = transformTree(tree.tpt, start)
    val rhs = transformTree(tree.rhs, start)
    var cur: Tree = cpy.ValDef(tree)(tree.name, tpt, rhs)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformValDef(cur.asInstanceOf[ValDef])
      idx += 1
    }
    cur
  }

  private def goDefDef(tree: DefDef, start: Int)(using Context): Tree = {
    val tpt = transformTree(tree.tpt, start)
    val paramss = tree.paramss.mapConserve(transformSpecificTrees(_, start))
      .asInstanceOf[List[ParamClause]]
    val rhs = transformTree(tree.rhs, start)
    var cur: Tree = cpy.DefDef(tree)(tree.name, paramss, tpt, rhs)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformDefDef(cur.asInstanceOf[DefDef])
      idx += 1
    }
    cur
  }

  private def goTypeDef(tree: TypeDef, start: Int)(using Context): Tree = {
    val rhs = transformTree(tree.rhs, start)
    var cur: Tree = cpy.TypeDef(tree)(tree.name, rhs)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTypeDef(cur.asInstanceOf[TypeDef])
      idx += 1
    }
    cur
  }

  private def goTemplate(tree: Template, start: Int)(using Context): Tree = {
    val constr = transformTree(tree.constr, start).asInstanceOf[DefDef]
    val parents = transformTrees(tree.parents, start)
    val self = transformTree(tree.self, start).asInstanceOf[ValDef]
    val body = transformStats(tree.body, tree.self, start)
    var cur: Tree = cpy.Template(tree)(constr, parents, Nil, self, body)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformTemplate(cur.asInstanceOf[Template])
      idx += 1
    }
    cur
  }

  private def goPackageDef(tree: PackageDef, start: Int)(using Context): Tree = {
    val pid = transformTree(tree.pid, start).asInstanceOf[RefTree]
    val stats = transformStats(tree.stats, NoTree, start)
    var cur: Tree = cpy.PackageDef(tree)(pid, stats)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformPackageDef(cur.asInstanceOf[PackageDef])
      idx += 1
    }
    cur
  }

  private def goCaseDef(tree: CaseDef, start: Int)(using Context): Tree = {
    val pat = transformTree(tree.pat, start)
    val guard = transformTree(tree.guard, start)
    val body = transformTree(tree.body, start)
    var cur: Tree = cpy.CaseDef(tree)(pat, guard, body)
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformCaseDef(cur.asInstanceOf[CaseDef])
      idx += 1
    }
    cur
  }

  private def goOther(tree: Tree, start: Int)(using Context): Tree = {
    var cur = tree
    var idx = start
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformOther(cur)
      idx += 1
    }
    cur
  }

  private def prepIdent(tree: Ident, start: Int)(using Context): Context = {
    var ctx1 = ctx
    var idx = start
    while (idx < miniPhases.length) {
      ctx1 = miniPhases(idx).prepareForIdent(tree)(using ctx1)
      idx += 1
    }
    ctx1
  }

  def transformTrees(trees: List[Tree], start: Int)(using Context): List[Tree] =
    trees.mapConserve(transformTree(_, start))

  def transformSpecificTrees[T <: Tree](trees: List[T], start: Int)(using Context): List[T] =
    trees.mapConserve(transformTree(_, start).asInstanceOf[T])

  def transformStats(trees: List[Tree], exprOwner: Tree, start: Int)(using Context): List[Tree] = {
    var idx = start
    while (idx < miniPhases.length) {
      miniPhases(idx).prepareForStats(trees)
      idx += 1
    }
    val trees1 = trees.mapConserve(transformTree(_, start))
    idx = start
    while (idx < miniPhases.length) {
      miniPhases(idx).transformStats(trees1)
      idx += 1
    }
    trees1
  }

  private def NoTree: Tree = ast.tpd.EmptyTree

  def transformUnit(tree: Tree)(using Context): Tree = {
    var cur = tree
    var idx = 0
    while (idx < miniPhases.length) {
      cur = miniPhases(idx).transformUnit(cur)
      idx += 1
    }
    cur
  }

  protected def run(using Context): Unit =
    ctx.compilationUnit.tpdTree =
      atPhase(miniPhases.last.next)(transformUnit(ctx.compilationUnit.tpdTree))

  // Initialization code
  // In the JS version, we conservatively assume all mini-phases define all methods
  // to avoid using java.lang.reflect.Method which is not available on Scala.js.

  private def newNxArray = new Array[MiniPhase | Null](miniPhases.length + 1)
  private val emptyNxArray = newNxArray

  // Conservative init: all mini-phases are always called for all tree types
  // This is less efficient but avoids reflection
  private def initConservative(): Array[MiniPhase | Null] = {
    val nx = newNxArray
    for (idx <- miniPhases.indices)
      nx(idx) = miniPhases(idx)
    nx
  }

  for (idx <- miniPhases.indices) {
    miniPhases(idx).superPhase = this
    miniPhases(idx).idxInGroup = idx
  }
}
