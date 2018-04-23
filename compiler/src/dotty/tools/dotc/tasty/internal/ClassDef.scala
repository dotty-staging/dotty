package dotty.tools.dotc.tasty
package internal

import dotty.tools.dotc.ast.{Trees, tpd}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols.ClassSymbol

import scala.tasty.trees
import scala.tasty.types

object ClassDef {

  def apply(tree: tpd.TypeDef)(implicit ctx: Context): trees.ClassDef = new Impl(tree)

  def apply(sym: ClassSymbol)(implicit ctx: Context): trees.ClassDef = {
    def toTree(sym: ClassSymbol): tpd.TypeDef = {
      val constr = tpd.DefDef(sym.unforcedDecls.find(_.isPrimaryConstructor).asTerm)
      val body = sym.unforcedDecls.filter(!_.isPrimaryConstructor).map(s =>
        if (s.isClass) toTree(s.asClass)
        else if (s.isType) tpd.TypeDef(s.asType)
        else if (s.is(Method)) tpd.DefDef(s.asTerm)
        else tpd.ValDef(s.asTerm)
      )
      val superArgs = Nil // TODO
      tpd.ClassDef(sym, constr, body, superArgs)
    }
    new Impl(toTree(sym))
  }

  def unapplyClassDef(arg: Impl): Option[trees.ClassDef.Data] = {
    implicit val ctx: Context = arg.ctx
    val Trees.TypeDef(name, impl@Trees.Template(constr, parents, self, _)) = arg.tree
    val className = TypeName(name)
    val constructor = DefDef(constr)
    val classParents = parents.map(p => if (!p.isType) Term(p) else TypeTree(p))
    val selfVal = if (self.isEmpty) None else Some(ValDef(self))
    val body = impl.body.map(Statement(_))
    val mods = Modifiers(arg.tree)
    Some((className, constructor, classParents, selfVal, body, mods))
  }

  private[tasty] class Impl(val tree: tpd.TypeDef)(implicit val ctx: Context) extends trees.ClassDef with Positioned {

    def tpe: types.Type = Type(tree.tpe)(ctx)

    def owner: trees.Definition = Definition(tree.symbol.owner)

    override def toString: String = {
      import Toolbox.extractor
      val trees.ClassDef(name, constructor, parents, self, body, mods) = this
      s"ClassDef($name, $constructor, $parents, $self, $body, $mods)"
    }
  }

}
