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
class PickleQuotes2 extends MacroTransform {
  import PickleQuotes2._
  import tpd._

  override def phaseName: String = PickleQuotes2.name

  override def allowsImplicitSearch: Boolean = true

  override def checkPostCondition(tree: Tree)(using Context): Unit =
    tree match
      case tree: RefTree if !Inliner.inInlineMethod =>
        assert(!tree.symbol.isQuote)
        assert(!tree.symbol.isExprSplice)
      case _ : TypeDef if !Inliner.inInlineMethod =>
        assert(!tree.symbol.hasAnnotation(defn.QuotedRuntime_SplicedTypeAnnot),
          s"${tree.symbol} should have been removed by PickledQuotes because it has a @quoteTypeTag")
      case _ =>

  override def run(using Context): Unit =
    if (ctx.compilationUnit.needsQuotePickling) super.run(using freshStagingContext)

  protected def newTransformer(using Context): Transformer = new Transformer {
    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      tree match
        case Apply(Select(Apply(TypeApply(fn, List(tpt)), List(code)),nme.apply), List(quotes))
        if fn.symbol == defn.QuotedRuntime_exprQuote =>
          val (splices, codeWithHoles) = makeHoles(code)
          val sourceRef = Inliner.inlineCallTrace(ctx.owner, tree.sourcePos)
          val codeWithHoles2 = Inlined(sourceRef, Nil, codeWithHoles)
          val pickled = ReifiedQuote(quotes, codeWithHoles2, splices, tpt.tpe, false)
          transform(pickled) // pickle quotes that are in the splices
        case Apply(TypeApply(_, List(tpt)), List(quotes)) if tree.symbol == defn.QuotedTypeModule_of =>
          tpt match
            case Select(t, _) if tpt.symbol == defn.QuotedType_splice =>
              // `Type.of[t.Underlying](quotes)`  --> `t`
              ref(t.symbol)
            case _ =>
              val (splices, tptWithHoles) = makeHoles(tpt)
              ReifiedQuote(quotes, tptWithHoles, splices, tpt.tpe, true)
        case tree: DefDef if tree.symbol.is(Macro) =>
          // Shrink size of the tree. The methods have already been inlined.
          // TODO move to FirstTransform to trigger even without quotes
          cpy.DefDef(tree)(rhs = defaultValue(tree.rhs.tpe))
        case _: DefDef if tree.symbol.isInlineMethod =>
          tree
        case _ =>
          super.transform(tree)
  }

  private def makeHoles(tree: tpd.Tree)(using Context): (List[Tree], tpd.Tree) =

    /** Remove references to local types that will not be defined in this quote */
    def getTypeHoleType(using Context) = new TypeMap() {
      override def apply(tp: Type): Type = tp match
        case tp: TypeRef if tp.typeSymbol.isTypeSplice =>
          apply(tp.dealias)
        case tp @ TypeRef(pre, _) if pre == NoPrefix || pre.termSymbol.isLocal =>
          val hiBound = tp.typeSymbol.info match
            case info: ClassInfo => info.parents.reduce(_ & _)
            case info => info.hiBound
          apply(hiBound)
        case tp =>
          mapOver(tp)
    }

    /** Remove references to local types that will not be defined in this quote */
    def getTermHoleType(using Context) = new TypeMap() {
      override def apply(tp: Type): Type = tp match
        case tp @ TypeRef(NoPrefix, _) =>
          // reference to term with a type defined in outer quote
          getTypeHoleType(tp)
        case tp @ TermRef(NoPrefix, _) =>
          // widen term refs to terms defined in outer quote
          apply(tp.widenTermRefExpr)
        case tp =>
          mapOver(tp)
    }

    class HoleMaker extends Transformer:
      private var splices = List.newBuilder[Tree]
      private var typeHoles = mutable.Map.empty[Symbol, Hole]
      private var idx = -1
      override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
        tree match
          case Apply(fn, List(splicedCode)) if fn.symbol == defn.QuotedRuntime_exprNestedSplice =>
            val Apply(Select(spliceFn, _), args) = splicedCode
            splices += spliceFn
            val holeArgs = args.map {
              case Apply(Select(Apply(_, code :: Nil), _), _) => code
              case Apply(TypeApply(_, List(code)), _) => code
            }
            idx += 1
            val holeType = getTermHoleType(tree.tpe)
            val hole = Hole(true, idx, holeArgs).withSpan(splicedCode.span).withType(holeType).asInstanceOf[Hole]
            Inlined(EmptyTree, Nil, hole).withSpan(tree.span)
          case Select(tp, _) if tree.symbol == defn.QuotedType_splice =>
            def makeTypeHole =
              splices += ref(tp.symbol)
              idx += 1
              val holeType = getTypeHoleType(tree.tpe)
              Hole(false, idx, Nil).withType(holeType).asInstanceOf[Hole]
            typeHoles.getOrElseUpdate(tp.symbol, makeTypeHole)
          case tree: DefTree =>
            val newAnnotations = tree.symbol.annotations.mapconserve { annot =>
              annot.derivedAnnotation(transform(annot.tree)(using ctx.withOwner(tree.symbol)))
            }
            tree.symbol.annotations = newAnnotations
            super.transform(tree)
          case _ =>
            super.transform(tree).withType(mapAnnots(tree.tpe))

      private def mapAnnots = new TypeMap { // TODO factor out duplicated logic in Splicing
        override def apply(tp: Type): Type = {
            tp match
              case tp @ AnnotatedType(underlying, annot) =>
                val underlying1 = this(underlying)
                derivedAnnotatedType(tp, underlying1, annot.derivedAnnotation(transform(annot.tree)))
              case _ => mapOver(tp)
        }
      }

      def getSplices() =
        val res = splices.result
        splices.clear()
        res
    end HoleMaker

    val holeMaker = new HoleMaker
    val newTree = holeMaker.transform(tree)
    (holeMaker.getSplices(), newTree)


  end makeHoles

}


object PickleQuotes2:
  val name: String = "pickleQuotes2"


object ReifiedQuote:
  import tpd._

  def apply(quotes: Tree, body: Tree, splices: List[Tree], originalTp: Type, isType: Boolean)(using Context) = {
    /** Encode quote using Reflection.Literal
      *
      *  Generate the code
      *  ```scala
      *    quotes => quotes.reflect.TreeMethods.asExpr(
      *      quotes.reflect.Literal.apply(x$1.reflect.Constant.<typeName>.apply(<literalValue>))
      *    ).asInstanceOf[scala.quoted.Expr[<body.type>]]
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def pickleAsLiteral(lit: Literal) = {
      val exprType = defn.QuotedExprClass.typeRef.appliedTo(body.tpe)
      val reflect = quotes.select("reflect".toTermName)
      val typeName = body.tpe.typeSymbol.name
      val literalValue =
        if lit.const.tag == Constants.NullTag || lit.const.tag == Constants.UnitTag then Nil
        else List(body)
      val constant = reflect.select(s"${typeName}Constant".toTermName).select(nme.apply).appliedToTermArgs(literalValue)
      val literal = reflect.select("Literal".toTermName).select(nme.apply).appliedTo(constant)
      reflect.select("TreeMethods".toTermName).select("asExpr".toTermName).appliedTo(literal).asInstance(exprType)
    }

    /** Encode quote using Reflection.Literal
      *
      *  Generate the code
      *  ```scala
      *    quotes => scala.quoted.ToExpr.{BooleanToExpr,ShortToExpr, ...}.apply(<literalValue>)(quotes)
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def liftedValue(lit: Literal, lifter: Symbol) =
      val exprType = defn.QuotedExprClass.typeRef.appliedTo(body.tpe)
      ref(lifter).appliedToType(originalTp).select(nme.apply).appliedTo(lit).appliedTo(quotes)

    def pickleAsValue(lit: Literal) = {
      // TODO should all constants be pickled as Literals?
      // Should examine the generated bytecode size to decide and performance
      lit.const.tag match {
        case Constants.NullTag => pickleAsLiteral(lit)
        case Constants.UnitTag => pickleAsLiteral(lit)
        case Constants.BooleanTag => liftedValue(lit, defn.ToExprModule_BooleanToExpr)
        case Constants.ByteTag => liftedValue(lit, defn.ToExprModule_ByteToExpr)
        case Constants.ShortTag => liftedValue(lit, defn.ToExprModule_ShortToExpr)
        case Constants.IntTag => liftedValue(lit, defn.ToExprModule_IntToExpr)
        case Constants.LongTag => liftedValue(lit, defn.ToExprModule_LongToExpr)
        case Constants.FloatTag => liftedValue(lit, defn.ToExprModule_FloatToExpr)
        case Constants.DoubleTag => liftedValue(lit, defn.ToExprModule_DoubleToExpr)
        case Constants.CharTag => liftedValue(lit, defn.ToExprModule_CharToExpr)
        case Constants.StringTag => liftedValue(lit, defn.ToExprModule_StringToExpr)
      }
    }

    /** Encode quote using QuoteUnpickler.{unpickleExpr, unpickleType}
      *
      *  Generate the code
      *  ```scala
      *    quotes => quotes.asInstanceOf[QuoteUnpickler].<unpickleExpr|unpickleType>[<type>](
      *      <pickledQuote>,
      *      <typeHole>,
      *      <termHole>,
      *    )
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def pickleAsTasty() = {
      def liftList(list: List[Tree], tpe: Type)(using Context): Tree =
        list.foldRight[Tree](ref(defn.NilModule)) { (x, acc) =>
          acc.select("::".toTermName).appliedToType(tpe).appliedTo(x)
        }

      val pickleQuote = PickledQuotes.pickleQuote(body)
      val pickledQuoteStrings = pickleQuote match
        case x :: Nil => Literal(Constant(x))
        case xs => liftList(xs.map(x => Literal(Constant(x))), defn.StringType)

      // TODO split holes earlier into types and terms. This all holes in each category can have consecutive indices
      val (typeSplices, termSplices) = splices.zipWithIndex.partition {
        case (splice, _) => splice.tpe.derivesFrom(defn.QuotedTypeClass)
      }

      // This and all closures in typeSplices are removed by the BetaReduce phase
      val typeHoles =
        if typeSplices.isEmpty then Literal(Constant(null)) // keep pickled quote without splices as small as possible
        else
          Lambda(
            MethodType(
              List("idx", "splices").map(name => UniqueName.fresh(name.toTermName).toTermName),
              List(defn.IntType, defn.SeqType.appliedTo(defn.AnyType)),
              defn.QuotedTypeClass.typeRef.appliedTo(WildcardType)),
            args => {
              val cases = typeSplices.map { case (splice, idx) =>
                CaseDef(Literal(Constant(idx)), EmptyTree, splice)
              }
              cases match
                case CaseDef(_, _, rhs) :: Nil => rhs
                case _ => Match(args(0).annotated(New(ref(defn.UncheckedAnnot.typeRef))), cases)
            }
          )

      // This and all closures in termSplices are removed by the BetaReduce phase
      val termHoles =
        if termSplices.isEmpty then Literal(Constant(null)) // keep pickled quote without splices as small as possible
        else
          Lambda(
            MethodType(
              List("idx", "splices", "quotes").map(name => UniqueName.fresh(name.toTermName).toTermName),
              List(defn.IntType, defn.SeqType.appliedTo(defn.AnyType), defn.QuotesClass.typeRef),
              defn.QuotedExprClass.typeRef.appliedTo(defn.AnyType)),
            args => {
              val cases = termSplices.map { case (splice, idx) =>
                val defn.FunctionOf(argTypes, defn.FunctionOf(quotesType :: _, _, _, _), _, _) = splice.tpe
                val rhs = {
                  val spliceArgs = argTypes.zipWithIndex.map { (argType, i) =>
                    args(1).select(nme.apply).appliedTo(Literal(Constant(i))).select(defn.Any_asInstanceOf).appliedToType(argType)
                  }
                  val Block(List(ddef: DefDef), _) = splice
                  // TODO: beta reduce inner closure? Or wait until BetaReduce phase?
                  BetaReduce(ddef, spliceArgs).select(nme.apply).appliedTo(args(2).asInstance(quotesType))
                }
                CaseDef(Literal(Constant(idx)), EmptyTree, rhs)
              }
              cases match
                case CaseDef(_, _, rhs) :: Nil => rhs
                case _ => Match(args(0).annotated(New(ref(defn.UncheckedAnnot.typeRef))), cases)
            }
          )

      val quoteClass = if isType then defn.QuotedTypeClass else defn.QuotedExprClass
      val quotedType = quoteClass.typeRef.appliedTo(originalTp)
      val lambdaTpe = MethodType(defn.QuotesClass.typeRef :: Nil, quotedType)
      val unpickleMeth = if isType then defn.QuoteUnpickler_unpickleType else defn.QuoteUnpickler_unpickleExpr
      quotes
        .asInstance(defn.QuoteUnpicklerClass.typeRef)
        .select(unpickleMeth).appliedToType(originalTp)
        .appliedTo(pickledQuoteStrings, typeHoles, termHoles).withSpan(body.span)
    }

    /** Encode quote using Reflection.TypeRepr.typeConstructorOf
      *
      *  Generate the code
      *  ```scala
      *    quotes.reflect.TypeReprMethods.asType(
      *      quotes.reflect.TypeRepr.typeConstructorOf(classOf[<type>]])
      *    ).asInstanceOf[scala.quoted.Type[<type>]]
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def taggedType() =
      val typeType = defn.QuotedTypeClass.typeRef.appliedTo(body.tpe)
      val classTree = TypeApply(ref(defn.Predef_classOf.termRef), body :: Nil)
      val reflect = quotes.select("reflect".toTermName)
      val typeRepr = reflect.select("TypeRepr".toTermName).select("typeConstructorOf".toTermName).appliedTo(classTree)
      reflect.select("TypeReprMethods".toTermName).select("asType".toTermName).appliedTo(typeRepr).asInstance(typeType)

    def getLiteral(tree: tpd.Tree): Option[Literal] = tree match
      case tree: Literal => Some(tree)
      case Block(Nil, e) => getLiteral(e)
      case Inlined(_, Nil, e) => getLiteral(e)
      case _ => None

    if (isType) then
      if splices.isEmpty && body.symbol.isPrimitiveValueClass then taggedType()
      else pickleAsTasty()
    else
      getLiteral(body) match
        case Some(lit) => pickleAsValue(lit)
        case _ => pickleAsTasty()
  }

end ReifiedQuote
