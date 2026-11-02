package dotty.tools
package dotc
package magic

import annotation.constructorOnly
import util.SourceFile

import core.*
import Contexts.Context
import ast.*

trait Untpd:
  import untpd.*

  case class Break()(using @constructorOnly src: SourceFile) extends Tree
  case class Continue()(using @constructorOnly src: SourceFile) extends Tree
  case class As(expr: Tree, id: Ident)(using @constructorOnly src: SourceFile) extends Tree
  case class Except(tpt: Tree, id: Ident, rhs: Tree)(using @constructorOnly src: SourceFile) extends Tree
  case class TryExcept(expr: Tree, excepts: List[Except], finalizer: Tree)(using @constructorOnly src: SourceFile) extends Tree
  case class PostfixIf(expr: Tree, cond: Tree, elsep: Tree)(using @constructorOnly src: SourceFile) extends Tree
  case class PostfixFor(expr: Tree, enums: List[Tree])(using @constructorOnly src: SourceFile) extends Tree

  /** Set or Dict literal. If Dict, exprs are two-elems Thickets, or if exprs
   *  consists of a single PostfixFor, its expr part is a two-elem Thicket.
   */
  case class SetOrDictLiteral(exprs: List[Tree])(using @constructorOnly src: SourceFile) extends Tree

  val magicCpy: MagicUntypedTreeCopier = MagicUntypedTreeCopier()

  trait MagicUntypedTreeMap(cpy: MagicUntypedTreeCopier = magicCpy) extends TreeMap:
    override def transformMoreCases(tree: Tree)(using Context): Tree = tree match
      case Break() => tree
      case Continue() => tree
      case As(expr: Tree, id: Ident) =>
        cpy.As(tree)(transform(expr), transformSub(id))
      case Except(tpt: Tree, id: Ident, rhs: Tree) =>
        cpy.Except(tree)(transform(tpt), transformSub(id), transform(rhs))
      case TryExcept(expr, excepts, finalizer) =>
        cpy.TryExcept(tree)(transform(expr), transformSub(excepts), transform(finalizer))
      case PostfixIf(expr, cond, elsep) =>
        cpy.PostfixIf(tree)(transform(expr), transform(cond), transform(elsep))
      case PostfixFor(expr, enums) =>
        cpy.PostfixFor(tree)(transform(expr), transform(enums))
      case SetOrDictLiteral(exprs) =>
        cpy.SetOrDictLiteral(tree)(transform(exprs))

  class MagicUntypedTreeCopier extends untpd.UntypedTreeCopier:
    def As(tree: Tree)(expr: Tree, id: Ident): Tree = tree match
      case tree: As if (expr eq tree.expr) && (id eq tree.id) => tree
      case _ => finalize(tree, Untpd.this.As(expr, id)(using tree.source))
    def Except(tree: Tree)(tpt: Tree, id: Ident, rhs: Tree): Tree = tree match
      case tree: Except if (tpt eq tree.tpt) && (id eq tree.id) && (rhs eq tree.rhs) => tree
      case _ => finalize(tree, Untpd.this.Except(tpt, id, rhs)(using tree.source))
    def TryExcept(tree: Tree)(expr: Tree, excepts: List[Except], finalizer: Tree): Tree = tree match
      case tree: TryExcept if (expr eq tree.expr) && (excepts eq tree.excepts) && (finalizer eq tree.finalizer) => tree
      case _ => finalize(tree, Untpd.this.TryExcept(expr, excepts, finalizer)(using tree.source))
    def PostfixIf(tree: Tree)(expr: Tree, cond: Tree, elsep: Tree): Tree = tree match
      case tree: PostfixIf if (expr eq tree.expr) && (cond eq tree.cond) && (elsep eq tree.elsep) => tree
      case _ => finalize(tree, Untpd.this.PostfixIf(expr, cond, elsep)(using tree.source))
    def PostfixFor(tree: Tree)(expr: Tree, enums: List[Tree]): Tree = tree match
      case tree: PostfixFor if (expr eq tree.expr) && (enums eq tree.enums) => tree
      case _ => finalize(tree, Untpd.this.PostfixFor(expr, enums)(using tree.source))
    def SetOrDictLiteral(tree: Tree)(exprs: List[Tree]): Tree = tree match
      case tree: SetOrDictLiteral if exprs eq tree.exprs => tree
      case _ => finalize(tree, Untpd.this.SetOrDictLiteral(exprs)(using tree.source))


