package dotty.tools.dotc
package quoted

import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Annotations.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.PatMatGivenVarName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.IllegalVariableInPatternAlternative
import dotty.tools.dotc.transform.SymUtils._

import scala.collection.mutable

object QuotePatterns:
  import tpd._

  /** TODO */
  def checkPattern(quotePattern: QuotePattern)(using Context): Unit = new tpd.TreeTraverser {
    def traverse(tree: Tree)(using Context): Unit = tree match {
      case _: SplicePattern =>
      case tdef: TypeDef if tdef.symbol.isClass =>
        val kind = if tdef.symbol.is(Module) then "objects" else "classes"
        report.error(em"Implementation restriction: cannot match $kind", tree.srcPos)
      case tree: NamedDefTree =>
        if tree.name.is(NameKinds.WildcardParamName) then
          report.warning(
            "Use of `_` for lambda in quoted pattern. Use explicit lambda instead or use `$_` to match any term.",
            tree.srcPos)
        if tree.name.isTermName && !tree.nameSpan.isSynthetic && tree.name != nme.ANON_FUN && tree.name.startsWith("$") then
          report.error("Names cannot start with $ quote pattern", tree.namePos)
        traverseChildren(tree)
      case _: Match =>
        report.error("Implementation restriction: cannot match `match` expressions", tree.srcPos)
      case _: Try =>
        report.error("Implementation restriction: cannot match `try` expressions", tree.srcPos)
      case _: Return =>
        report.error("Implementation restriction: cannot match `return` statements", tree.srcPos)
      case _ =>
        traverseChildren(tree)
    }

  }.traverse(quotePattern.body)

  def encode(quotePattern: QuotePattern)(using Context): Tree =
    val quoteClass = if (quotePattern.body.isTerm) defn.QuotedExprClass else defn.QuotedTypeClass

    val matchModule = if quotePattern.body.isTerm then defn.QuoteMatching_ExprMatch else defn.QuoteMatching_TypeMatch
    val unapplyFun = quotePattern.quotes.asInstance(defn.QuoteMatchingClass.typeRef).select(matchModule).select(nme.unapply)

    val typeBindingsTuple = tpd.hkNestedPairsTypeTree(quotePattern.bindings)

    val (splicePatterns, shape0) = splitQuotePattern(quotePattern.body)

    val shape1 =
      if quotePattern.bindings.isEmpty then shape0
      else
        val patternTypes = quotePattern.bindings.map { binding =>
          val sym = binding.symbol
          val typeSym = newSymbol(ctx.owner, sym.name ++ "$inPattern" /* TODO remove $inPattern */, EmptyFlags, sym.info, NoSymbol, binding.span)
          typeSym.addAnnotation(Annotation(New(ref(defn.QuotedRuntimePatterns_patternTypeAnnot.typeRef)).withSpan(binding.span)))
          TypeDef(typeSym.asType).withSpan(binding.span)
        }
        new TreeTypeMap(
          substFrom = quotePattern.bindings.map(_.symbol),
          substTo = patternTypes.map(_.symbol)
        ).transform(Block(patternTypes, shape0))


    val quotedShape =
      if (quotePattern.body.isTerm) tpd.Quote(shape1).select(nme.apply).appliedTo(quotePattern.quotes)
      else ref(defn.QuotedTypeModule_of.termRef).appliedToTypeTree(shape1).appliedTo(quotePattern.quotes)

    val givenTypes = quotePattern.bindings.map { binding =>
      val name = binding.symbol.name.toTypeName
      val nameOfSyntheticGiven = PatMatGivenVarName.fresh(name.toTermName)
      val tpe = defn.QuotedTypeClass.typeRef.appliedTo(binding.symbol.typeRef)
      val givenTypeSym = newPatternBoundSymbol(nameOfSyntheticGiven, tpe, binding.span, flags = Given)
      Bind(givenTypeSym, untpd.Ident(nme.WILDCARD).withType(tpe)).withSpan(binding.span)
    }

    val patterns = givenTypes ::: splicePatterns
    val patternTypes = patterns.map(_.tpe.widenTermRefExpr)

    val splicePat =
      if patterns.isEmpty then ref(defn.EmptyTupleModule.termRef)
      else if patterns.size <= Definitions.MaxTupleArity then
        val tupleNUnapply =
          ref(defn.TupleType(patterns.size).nn.typeSymbol.companionModule)
            .select(nme.unapply)
            .appliedToTypes(patternTypes)
        UnApply(tupleNUnapply, Nil, patterns, defn.tupleType(patternTypes))
      else ???

    val patType =
      val quotedTypes =
        quotePattern.bindings.map(givenType => defn.QuotedTypeClass.typeRef.appliedTo(givenType.symbol.typeRef))
      val quotedExprs =
        splicePatterns.map(_.tpe.widenTermRefExpr)
      defn.tupleType(quotedTypes :::quotedExprs)

    UnApply(
      fun = unapplyFun.appliedToTypeTrees(typeBindingsTuple :: TypeTree(patType) :: Nil),
      implicits = quotedShape :: Nil,
      patterns = splicePat :: Nil,
      quotePattern.tpe)

   /** TODO update
    *
    * Split a typed quoted pattern is split into its type bindings, pattern expression and inner patterns.
   *  Type definitions with `@patternType` will be inserted in the pattern expression for each type binding.
   *
   *  A quote pattern
   *  ```
   *  case '{ type ${given t$giveni: Type[t @ _]}; ${ls: Expr[List[t]]} } => ...
   *  ```
   *  will return
   *  ```
   *  (
   *    Map(<t$giveni>: Symbol -> <t @ _>: Bind),
   *    <'{
   *       @scala.internal.Quoted.patternType type t
   *       scala.internal.Quoted.patternHole[List[t]]
   *    }>: Tree,
   *    List(<ls: Expr[List[t]]>: Tree)
   *  )
   *  ```
   */
  private def splitQuotePattern(body: Tree)(using Context): (List[Tree], Tree) = {
    val patBuf = new mutable.ListBuffer[Tree]
    val shape = new tpd.TreeMap {
      override def transform(tree: Tree)(using Context) = tree match {
        case Typed(splice @ SplicePattern(pat, Nil), tpt) if !tpt.tpe.derivesFrom(defn.RepeatedParamClass) =>
          transform(tpt) // Collect type bindings
          transform(splice)
        case SplicePattern(pat, args) =>
          val patType = pat.tpe.widen
          val patType1 = patType.translateFromRepeated(toArray = false)
          val pat1 = if (patType eq patType1) pat else pat.withType(patType1)
          patBuf += pat1
          if args.isEmpty then ref(defn.QuotedRuntimePatterns_patternHole.termRef).appliedToType(tree.tpe).withSpan(tree.span)
          else ref(defn.QuotedRuntimePatterns_higherOrderHole.termRef).appliedToType(tree.tpe).appliedTo(SeqLiteral(args, TypeTree(defn.AnyType))).withSpan(tree.span)
        case _ =>
          super.transform(tree)
      }
    }.transform(body)
    (patBuf.toList, shape)
  }