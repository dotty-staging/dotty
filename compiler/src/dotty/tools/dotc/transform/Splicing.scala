package dotty.tools.dotc
package transform

import core._
import Decorators._
import Flags._
import Types._
import Contexts._
import Symbols._
import Constants._
import ast.Trees._
import ast.{TreeTypeMap, untpd}
import util.Spans._
import tasty.TreePickler.Hole
import SymUtils._
import NameKinds._
import dotty.tools.dotc.ast.tpd
import typer.Implicits.SearchFailureType

import scala.collection.mutable
import dotty.tools.dotc.core.Annotations._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.quoted._
import dotty.tools.dotc.transform.TreeMapWithStages._
import dotty.tools.dotc.typer.Inliner

import scala.annotation.constructorOnly


/** Translates quoted terms and types to `unpickleExpr` or `unpickleType` method calls.
 *
 *  Transforms top level quote
 *   ```
 *   '{ ...
 *      val x1 = ???
 *      val x2 = ???
 *      ...
 *      ${ ... '{ ... x1 ... x2 ...} ... }
 *      ...
 *    }
 *    ```
 *  to
 *    ```
 *     unpickleExpr(
 *       pickled = [[ // PICKLED TASTY
 *         ...
 *         val x1 = ???
 *         val x2 = ???
 *         ...
 *         Hole(<i> | x1, x2)
 *         ...
 *       ]],
 *       typeHole = (idx: Int, args: List[Any]) => idx match {
 *         case 0 => ...
 *       },
 *       termHole = (idx: Int, args: List[Any], quotes: Quotes) => idx match {
 *         case 0 => ...
 *         ...
 *         case <i> =>
 *           val x1$1 = args(0).asInstanceOf[Expr[T]]
 *           val x2$1 = args(1).asInstanceOf[Expr[T]] // can be asInstanceOf[Type[T]]
 *            ...
 *           { ... '{ ... ${x1$1} ... ${x2$1} ...} ... }
 *       },
 *     )
 *    ```
 *  and then performs the same transformation on `'{ ... ${x1$1} ... ${x2$1} ...}`.
 *
 */
class Splicing extends MacroTransform {
  import Splicing._
  import tpd._

  override def phaseName: String = Splicing.name

  override def allowsImplicitSearch: Boolean = true

  override def checkPostCondition(tree: Tree)(using Context): Unit =
    ()

  override def run(using Context): Unit =
    if (ctx.compilationUnit.needsQuotePickling) super.run(using freshStagingContext)

  protected def newTransformer(using Context): Transformer = MyTransformer

  object MyTransformer extends Transformer:
    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      tree match
        case Apply(Select(Apply(TypeApply(fn,_), List(code)),nme.apply),List(quotes))
        if fn.symbol == defn.QuotedRuntime_exprQuote =>
          val quoteTransformer = new QuoteTransformer
          quoteTransformer.transform(tree)
        case _ =>
          super.transform(tree)
  end MyTransformer

  class QuoteTransformer extends Transformer:
    private val localDefs = mutable.Set.empty[Symbol]
    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      tree match
        case Apply(fn, List(splicedCode)) if fn.symbol == defn.QuotedRuntime_exprNestedSplice =>
          val spliceTransformer = new SpliceTransformer(localDefs.toSet, ctx.owner)
          val newSplicedCode1 = spliceTransformer.transformSplice(splicedCode)
          val newSplicedCode2 = MyTransformer.transform(newSplicedCode1)
          fn.appliedTo(newSplicedCode2)
        case tree: DefTree =>
          localDefs += tree.symbol
          transformAnnotations(tree)
          super.transform(tree)
        case _ =>
          super.transform(tree).withType(mapAnnots(tree.tpe))

    private def transformAnnotations(tree: DefTree)(using Context): Unit =
      tree.symbol.annotations = tree.symbol.annotations.mapconserve { annot =>
        val newAnnotTree = transform(annot.tree)(using ctx.withOwner(tree.symbol))
        if (annot.tree == newAnnotTree) annot
        else ConcreteAnnotation(newAnnotTree)
      }
    private def mapAnnots(using Context) = new TypeMap {
      override def apply(tp: Type): Type = {
          tp match
            case tp @ AnnotatedType(underlying, annot) =>
              val underlying1 = this(underlying)
              derivedAnnotatedType(tp, underlying1, annot.derivedAnnotation(transform(annot.tree)))
            case _ => mapOver(tp)
      }
    }

  end QuoteTransformer

  class SpliceTransformer(quoteDefs: Set[Symbol], spliceOwner: Symbol) extends Transformer:
    private var refBindingMap = mutable.Map.empty[Symbol, (Tree, Symbol)]
    private var level = 0
    private var quotes: Tree = null

    def transformSplice(tree: tpd.Tree)(using Context): tpd.Tree =
      val newTree = transform(tree)
      val (refs, bindings) = refBindingMap.values.toList.unzip
      val bindingsTypes = bindings.map(_.termRef.widenTermRefExpr)
      val methType = MethodType(bindingsTypes, newTree.tpe)
      val meth = newSymbol(spliceOwner, nme.ANON_FUN, Synthetic | Method, methType)
      val ddef = DefDef(meth, List(bindings), newTree.tpe, newTree.changeOwner(ctx.owner, meth))
      val fnType = defn.FunctionType(bindings.size, isContextual = false).appliedTo(bindingsTypes :+ newTree.tpe)
      val closure = Block(ddef :: Nil, Closure(Nil, ref(meth), TypeTree(fnType)))
      closure.select(nme.apply).appliedToArgs(refs)

    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      tree match
        case tree: RefTree =>
          if tree.isTerm then
            if quoteDefs.contains(tree.symbol) then
                  splicedTerm(tree).spliced(tree.tpe.widenTermRefExpr)
            else super.transform(tree)
          else // tree.isType then
            if containsCapturedType(tree.tpe) then
              splicedType(tree).select(defn.QuotedType_splice)
            else super.transform(tree)
        case tree: TypeTree =>
          if containsCapturedType(tree.tpe) then
            splicedType(tree).select(defn.QuotedType_splice)
          else tree
        case Assign(lhs: RefTree, rhs) =>
          if quoteDefs.contains(lhs.symbol) then transformSplicedAssign(lhs, rhs, tree.tpe)
          else super.transform(tree)
        case Apply(fn, args) if fn.symbol == defn.QuotedRuntime_exprNestedSplice =>
          level -= 1
          val newArgs = args.mapConserve(transform)
          level += 1
          cpy.Apply(tree)(fn, newArgs)
        case Apply(sel @ Select(app @ Apply(fn, args),nme.apply), quotesArgs)
        if fn.symbol == defn.QuotedRuntime_exprQuote =>
          args match
          case List(tree: RefTree) if quoteDefs.contains(tree.symbol) =>
            splicedTerm(tree)
          case _ =>
            val oldQuotes = quotes
            if level == 0 then quotes = quotesArgs.head
            level += 1
            val newArgs = args.mapConserve(transform)
            level -= 1
            quotes = oldQuotes
            cpy.Apply(tree)(cpy.Select(sel)(cpy.Apply(app)(fn, newArgs), nme.apply), quotesArgs)
        case Apply(TypeApply(_, List(tpt: Ident)), List(quotes))
        if tree.symbol == defn.QuotedTypeModule_of && quoteDefs.contains(tpt.symbol) =>
          splicedType(tpt)
        case _ =>
          super.transform(tree)

    private def containsCapturedType(tpe: Type)(using Context): Boolean =
      tpe match
        case tpe @ TypeRef(prefix, _) => quoteDefs.contains(tpe.symbol) || containsCapturedType(prefix)
        case tpe @ TermRef(prefix, _) => quoteDefs.contains(tpe.symbol) || containsCapturedType(prefix)
        case AppliedType(tycon, args) => containsCapturedType(tycon) || args.exists(containsCapturedType)
        case _ => false

    private def transformSplicedAssign(lhs: RefTree, rhs: Tree, tpe: Type)(using Context): Tree =
      // Make `(x: T) => rhs = x`
      val methTpe = MethodType(List(lhs.tpe.widenTermRefExpr), tpe)
      val meth = newSymbol(spliceOwner, nme.ANON_FUN, Synthetic | Method, methTpe)
      val closure = Closure(meth, args => Assign(lhs, args.head.head))

      val binding = splicedTerm(closure)
      // TODO: `${Expr.betaReduce('{$rhsFn.apply(lhs)})}` ?
      // Make `$rhsFn.apply(lhs)`
      binding.spliced(methTpe.toFunctionType(isJava = false)).select(nme.apply).appliedTo(transform(rhs))

    private def splicedTerm(tree: Tree)(using Context): Tree =
      val tpe = tree.tpe.widenTermRefExpr match {
        case tpw: MethodicType => tpw.toFunctionType(isJava = false)
        case tpw => tpw
      }
      def newBinding = newSymbol(
        spliceOwner,
        UniqueName.fresh(tree.symbol.name.toTermName).toTermName,
        Param,
        tpe.exprType,
      )
      val (_, bindingSym) = refBindingMap.getOrElseUpdate(tree.symbol, (tree.quoted, newBinding))
      ref(bindingSym)

    private def splicedType(tree: Tree)(using Context): Tree =
      val tpe = tree.tpe.widenTermRefExpr
      def newBinding = newSymbol(
        spliceOwner,
        UniqueName.fresh(nme.Type).toTermName,
        Param,
        defn.QuotedTypeClass.typeRef.appliedTo(tpe),
      )
      val (_, bindingSym) = refBindingMap.getOrElseUpdate(tree.symbol, (tree.tpe.quoted, newBinding))
      ref(bindingSym)

  end SpliceTransformer

}

object Splicing {
  import tpd._

  val name: String = "splicing"

  extension (tree: Tree)(using Context)
    def spliced(tpe: Type): Tree =
      val exprTpe = defn.QuotedExprClass.typeRef.appliedTo(tpe)
      val closure =
        val methTpe = ContextualMethodType(List(defn.QuotesClass.typeRef), exprTpe)
        val meth = newSymbol(ctx.owner, nme.ANON_FUN, Synthetic | Method, methTpe)
        Closure(meth, _ => tree.changeOwner(ctx.owner, meth))
      ref(defn.QuotedRuntime_exprNestedSplice)
        .appliedToType(tpe)
        .appliedTo(Literal(Constant(null))) // Dropped when creating the Hole that contains it
        .appliedTo(closure)

    def quoted: Tree =
      val tpe = tree.tpe.widenTermRefExpr
      ref(defn.QuotedRuntime_exprQuote)
        .appliedToType(tpe)
        .appliedTo(tree)
        .select(nme.apply)
        .appliedTo(Literal(Constant(null))) // Dropped when creating the Hole that contains it
  end extension

  extension (tpe: Type)(using Context)
    def exprType: Type =
      defn.QuotedExprClass.typeRef.appliedTo(tpe)
    def quoted: Tree =
      ref(defn.QuotedTypeModule_of)
        .appliedToType(tpe)
        .appliedTo(Literal(Constant(null))) // Dropped when creating the Hole that contains it
  end extension

}
