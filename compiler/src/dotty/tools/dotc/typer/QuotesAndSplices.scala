package dotty.tools.dotc
package typer

import dotty.tools.dotc.ast._
import dotty.tools.dotc.config.Feature._
import dotty.tools.dotc.config.SourceVersion._
import dotty.tools.dotc.core._
import dotty.tools.dotc.core.Annotations._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.NameKinds.PatMatGivenVarName
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.inlines.PrepareInlineable
import dotty.tools.dotc.quoted.QuotePatterns
import dotty.tools.dotc.staging.StagingLevel.*
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.typer.Implicits._
import dotty.tools.dotc.typer.Inferencing._
import dotty.tools.dotc.util.Property
import dotty.tools.dotc.util.Spans._
import dotty.tools.dotc.util.Stats.record
import dotty.tools.dotc.reporting.IllegalVariableInPatternAlternative
import scala.collection.mutable

/** Type quotes `'{ ... }` and splices `${ ... }` */
trait QuotesAndSplices {
  self: Typer =>

  import tpd.*
  import QuotesAndSplices.*

  /** Translate `'{ e }` into `scala.quoted.Expr.apply(e)` and `'[T]` into `scala.quoted.Type.apply[T]`
   *  while tracking the quotation level in the context.
   */
  def typedQuote(tree: untpd.Quote, pt: Type)(using Context): Tree = {
    record("typedQuote")
    tree.body match {
      case _: untpd.Splice if tree.isTerm && !ctx.mode.is(Mode.Pattern) =>
        report.warning("Canceled splice directly inside a quote. '{ ${ XYZ } } is equivalent to XYZ.", tree.srcPos)
      case _ =>
    }
    val quotes = inferImplicitArg(defn.QuotesClass.typeRef, tree.span)

    if quotes.tpe.isInstanceOf[SearchFailureType] then
      report.error(missingArgMsg(quotes, defn.QuotesClass.typeRef, ""), ctx.source.atSpan(tree.span))
    else if !quotes.tpe.isStable then
      report.error(em"Quotes require stable Quotes, but found non stable $quotes", quotes.srcPos)

    if ctx.mode.is(Mode.Pattern) then
      typedQuotePattern0(tree, pt, quotes).withSpan(tree.span)
    else if tree.isTypeQuote then
      val msg = em"""Quoted types `'[..]` can only be used in patterns.
                    |
                    |Hint: To get a scala.quoted.Type[T] use scala.quoted.Type.of[T] instead.
                    |"""
      report.error(msg, tree.srcPos)
      EmptyTree
    else
      // TODO typecheck directly (without `exprQuote`)
      val exprQuoteTree = untpd.Apply(untpd.ref(defn.QuotedRuntime_exprQuote.termRef), tree.body)
      val quotedExpr = typedApply(exprQuoteTree, pt)(using quoteContext) match
        case Apply(TypeApply(fn, tpt :: Nil), quotedExpr :: Nil) => untpd.Quote(quotedExpr).withBodyType(tpt.tpe)
      makeInlineable(quotedExpr.select(nme.apply).appliedTo(quotes).withSpan(tree.span))
  }

  private def makeInlineable(tree: Tree)(using Context): Tree =
    inContext(ctx.withOwner(ctx.owner.skipLocalOwners)) {
      PrepareInlineable.makeInlineable(tree)
    }

  /** Translate `${ t: Expr[T] }` into expression `t.splice` while tracking the quotation level in the context */
  def typedSplice(tree: untpd.Splice, pt: Type)(using Context): Tree = {
    record("typedSplice")
    checkSpliceOutsideQuote(tree)
    assert(!ctx.mode.is(Mode.QuotedPattern))
    tree.expr match {
      case untpd.Quote(innerExpr) if innerExpr.isTerm =>
        report.warning("Canceled quote directly inside a splice. ${ '{ XYZ } } is equivalent to XYZ.", tree.srcPos)
        return typed(innerExpr, pt)
      case _ =>
    }
    if (level == 0) {
      // Mark the first inline method from the context as a macro
      def markAsMacro(c: Context): Unit =
        if (c.owner eq c.outer.owner) markAsMacro(c.outer)
        else if (c.owner.isInlineMethod) c.owner.setFlag(Macro)
        else if (!c.outer.owner.is(Package)) markAsMacro(c.outer)
        else assert(ctx.reporter.hasErrors) // Did not find inline def to mark as macro
      markAsMacro(ctx)
    }

    // TODO typecheck directly (without `exprSplice`)
    val internalSplice =
      untpd.Apply(untpd.ref(defn.QuotedRuntime_exprSplice.termRef), tree.expr)
    typedApply(internalSplice, pt)(using spliceContext).withSpan(tree.span) match
      case tree @ Apply(TypeApply(_, tpt :: Nil), spliced :: Nil) if tree.symbol == defn.QuotedRuntime_exprSplice =>
        cpy.Splice(tree)(spliced)
      case tree => tree
  }

  def typedQuotePattern(tree: untpd.QuotePattern, pt: Type)(using Context): Tree = ???

  def typedSplicePattern(tree: untpd.SplicePattern, pt: Type)(using Context): Tree = {
    record("typedSplicePattern")
    if (isFullyDefined(pt, ForceDegree.flipBottom)) {
      def spliceOwner(ctx: Context): Symbol =
        if (ctx.mode.is(Mode.QuotedPattern)) spliceOwner(ctx.outer) else ctx.owner
      val typedArgs = tree.args.map {
        case arg: untpd.Ident =>
          typedExpr(arg)
        case arg =>
          report.error("Open pattern expected an identifier", arg.srcPos)
          EmptyTree
      }
      for arg <- typedArgs if arg.symbol.is(Mutable) do // TODO support these patterns. Possibly using scala.quoted.util.Var
        report.error("References to `var`s cannot be used in higher-order pattern", arg.srcPos)
      val argTypes = typedArgs.map(_.tpe.widenTermRefExpr)
      val patType = if tree.args.isEmpty then pt else defn.FunctionOf(argTypes, pt)
      val pat = typedPattern(tree.body, defn.QuotedExprClass.typeRef.appliedTo(patType))(
        using spliceContext.retractMode(Mode.QuotedPattern).addMode(Mode.Pattern).withOwner(spliceOwner(ctx)))
      val baseType = pat.tpe.baseType(defn.QuotedExprClass)
      val argType = if baseType != NoType then baseType.argTypesHi.head else defn.NothingType
      untpd.cpy.SplicePattern(tree)(pat, typedArgs).withType(pt)
    }
    else {
      report.error(em"Type must be fully defined.\nConsider annotating the splice using a type ascription:\n  ($tree: XYZ).", tree.body.srcPos)
      tree.withType(UnspecifiedErrorType)
    }
  }

  def typedHole(tree: untpd.Hole, pt: Type)(using Context): Tree =
    val tpt = typedType(tree.tpt)
    assignType(tree, tpt)

  /** Types a splice applied to some arguments `$f(arg1, ..., argn)` in a quote pattern.
   *
   *  The tree is desugared into `$f.apply(arg1, ..., argn)` where the expression `$f`
   *  is expected to type as a function type `(T1, ..., Tn) => R`.
   *  `Ti` is the type of the argument `argi` and R if the type of the prototype.
   *  The prototype must be fully defined to be able to infer the type of `R`.
   */
  def typedAppliedSplice(tree: untpd.Apply, pt: Type)(using Context): Tree = {
    assert(ctx.mode.is(Mode.QuotedPattern))
    val untpd.Apply(splice: untpd.SplicePattern, args) = tree: @unchecked
    def isInBraces: Boolean = splice.span.end != splice.body.span.end
    if isInBraces then // ${x}(...) match an application
      val typedArgs = args.map(arg => typedExpr(arg))
      val argTypes = typedArgs.map(_.tpe.widenTermRefExpr)
      val splice1 = typedSplicePattern(splice, defn.FunctionOf(argTypes, pt))
      untpd.cpy.Apply(tree)(splice1.select(nme.apply), typedArgs).withType(pt)
    else // $x(...) higher-order quasipattern
      if args.isEmpty then
         report.error("Missing arguments for open pattern", tree.srcPos)
      typedSplicePattern(untpd.cpy.SplicePattern(tree)(splice.body, args), pt)
  }

  /** Type a pattern variable name `t` in quote pattern as `${given t$giveni: Type[t @ _]}`.
   *  The resulting pattern is the split in `splitQuotePattern`.
   */
  def typedQuotedTypeVar(tree: untpd.Ident, pt: Type)(using Context): Tree =
    val typeSymInfo = pt match
      case pt: TypeBounds => pt
      case _ => TypeBounds.empty
    getQuotedPatternTypeVariable(tree.name.asTypeName) match
      case Some(typeSym) =>
        checkExperimentalFeature(
          "support for multiple references to the same type (without backticks) in quoted type patterns (SIP-53)",
          tree.srcPos,
          "\n\nSIP-53: https://docs.scala-lang.org/sips/quote-pattern-type-variable-syntax.html")
        if !(typeSymInfo =:= TypeBounds.empty) && !(typeSym.info <:< typeSymInfo) then
          report.warning(em"Ignored bound$typeSymInfo\n\nConsider defining bounds explicitly `'{ $typeSym$typeSymInfo; ... }`", tree.srcPos)
        ref(typeSym)
      case None =>
        if ctx.mode.is(Mode.InPatternAlternative) then
          report.error(IllegalVariableInPatternAlternative(tree.name), tree.srcPos)
        def spliceOwner(ctx: Context): Symbol =
          if (ctx.mode.is(Mode.QuotedPattern)) spliceOwner(ctx.outer) else ctx.owner
        val typeSym = inContext(ctx.withOwner(spliceOwner(ctx))) {
          newPatternBoundSymbol(tree.name, typeSymInfo, tree.span, addToGadt = false)
        }
        addQuotedPatternTypeVariable(typeSym)
        Bind(typeSym, untpd.Ident(nme.WILDCARD).withType(typeSymInfo)).withSpan(tree.span)


  private def checkSpliceOutsideQuote(tree: untpd.Tree)(using Context): Unit =
    if (level == 0 && !ctx.owner.ownersIterator.exists(_.isInlineMethod))
      report.error("Splice ${...} outside quotes '{...} or inline method", tree.srcPos)
    else if (level < 0)
      report.error(
        em"""Splice $${...} at level $level.
            |
            |Inline method may contain a splice at level 0 but the contents of this splice cannot have a splice.
            |""", tree.srcPos
      )

  /** Split a typed quoted pattern is split into its type bindings, pattern expression and inner patterns.
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
  private def checkQuotePattern(quoted: Tree)(using Context): Unit = {
    new tpd.TreeTraverser {
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
          if tree.name.isTermName && !tree.nameSpan.isSynthetic && tree.name.startsWith("$") then
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

    }.traverse(quoted)
  }

  /** Type a quote pattern `case '{ <quoted> } =>` qiven the a current prototype. Typing the pattern
   *  will also transform it into a call to `scala.internal.quoted.Expr.unapply`.
   *
   *  Code directly inside the quote is typed as an expression using Mode.QuotedPattern. Splices
   *  within the quotes become patterns again and typed accordingly.
   *
   *  ```
   *  case '{ ($ls: List[t]) } =>
   *    // `t$giveni` is of type `Type[t]` for some unknown `t`
   *    // `t$giveni` is implicitly available
   *    // `ls` is of type `Expr[List[t]]`
   *    '{ val h: $t = $ls.head  }
   *  ```
   *
   *  For each type splice we will create a new type binding in the pattern match (`t @ _` in this case)
   *  and a corresponding type in the quoted pattern as a hole (`@patternType type t` in this case).
   *  All these generated types are inserted at the start of the quoted code.
   *
   *  After typing the tree will resemble
   *
   *  ```
   *  case '{ type ${given t$giveni: Type[t @ _]}; ${ls: Expr[List[t]]} } => ...
   *  ```
   *
   *  Then the pattern is _split_ into the expression contained in the pattern replacing the splices by holes,
   *  and the patterns in the splices. All these are recombined into a call to `Matcher.unapply`.
   *
   *  ```
   *  case scala.internal.quoted.Expr.unapply[
   *          KList[t @ _, KNil], // Type binging definition
   *          Tuple2[Type[t], Expr[List[t]]] // Typing the result of the pattern match
   *        ](
   *          Tuple2.unapply
   *            [Type[t], Expr[List[t]]] //Propagated from the tuple above
   *            (given t$giveni @ _, ls @ _: Expr[List[t]]) // from the spliced patterns
   *        )(
   *         '{ // Runtime quote Matcher.unapply uses to mach against. Expression directly inside the quoted pattern without the splices
   *            @scala.internal.Quoted.patternType type t
   *            scala.internal.Quoted.patternHole[List[t]]
   *          },
   *          true, // If there is at least one type splice. Used to instantiate the context with or without GADT constraints
   *          x$2 // tasty.Reflection instance
   *        ) => ...
   *  ```
   */
  private def typedQuotePattern0(tree: untpd.Quote, pt: Type, quotes: Tree)(using Context): Tree = {
    val quoted = tree.body
    if quoted.isTerm && !pt.derivesFrom(defn.QuotedExprClass) then
      report.error("Quote pattern can only match scrutinees of type scala.quoted.Expr", tree.srcPos)
    else if quoted.isType && !pt.derivesFrom(defn.QuotedTypeClass) then
      report.error("Quote pattern can only match scrutinees of type scala.quoted.Type", tree.srcPos)

    val exprPt = pt.baseType(if quoted.isType then defn.QuotedTypeClass else defn.QuotedExprClass)
    val quotedPt = exprPt.argInfos.headOption match {
      case Some(argPt: ValueType) => argPt // excludes TypeBounds
      case _ => defn.AnyType
    }
    val (untpdExplicitTypeVariables, quoted0) = desugar.quotedPatternTypeVariables(desugar.quotedPattern(quoted, untpd.TypedSplice(TypeTree(quotedPt))))

    for untpdExplicitTypeVariable <- untpdExplicitTypeVariables do
      if ctx.mode.is(Mode.InPatternAlternative) then
        report.error(IllegalVariableInPatternAlternative(untpdExplicitTypeVariable.name), untpdExplicitTypeVariable.srcPos)
      untpdExplicitTypeVariable.rhs match
        case _: TypeBoundsTree => // ok
        case LambdaTypeTree(_, body: TypeBoundsTree) => // ok
        case _ => report.error("Quote type variable definition cannot be an alias", untpdExplicitTypeVariable.srcPos)

    if quoted.isType && untpdExplicitTypeVariables.nonEmpty then
      checkExperimentalFeature(
        "explicit type variable declarations quoted type patterns (SIP-53)",
        untpdExplicitTypeVariables.head.srcPos,
        "\n\nSIP-53: https://docs.scala-lang.org/sips/quote-pattern-type-variable-syntax.html")

    val (typedExplicitTypeVariables, patternCtx) =
      val quoteCtx = quotePatternContext()
      if untpdExplicitTypeVariables.isEmpty then (Nil, quoteCtx)
      else typedBlockStats(untpdExplicitTypeVariables)(using quoteCtx)

    val patternBody1 = inContext(patternCtx) {
      for typeVariable <- typedExplicitTypeVariables do
        addQuotedPatternTypeVariable(typeVariable.symbol)

      if quoted.isType then typedType(quoted0, WildcardType)(using patternCtx)
      else typedExpr(quoted0, WildcardType)
    }

    val explicitTypeBindings = typedExplicitTypeVariables.map(tdef => {
      val sym = tdef.symbol
      val newSym = newPatternBoundSymbol(sym.name, sym.info, sym.span, addToGadt = false)
      Bind(newSym, untpd.Ident(nme.WILDCARD).withType(sym.info)).withSpan(quoted.span)
    })

    val proto =
      val quoteType = patternBody1.tpe.subst(typedExplicitTypeVariables.map(_.symbol), explicitTypeBindings.map(_.symbol.typeRef))
      val quoteClass = if quoted.isTerm then defn.QuotedExprClass else defn.QuotedTypeClass
      quoteClass.typeRef.appliedTo(quoteType & quotedPt)

    val nestedTypeBindings = mutable.ListBuffer.empty[Bind]
    val patternBody2 = new TreeTypeMap(
      treeMap = _ match {
        case tree: Bind if tree.symbol.isType =>
          nestedTypeBindings += tree
          tpd.ref(tree.symbol)
        case tree => tree
      },
      substFrom = typedExplicitTypeVariables.map(_.symbol),
      substTo = explicitTypeBindings.map(_.symbol),
    ).transform(patternBody1)

    val typeBindings = explicitTypeBindings ++ nestedTypeBindings
    val newQuotePattern = QuotePattern(typeBindings, patternBody2, quotes, proto)
    QuotePatterns.checkPattern(newQuotePattern)

    val encoded = QuotePatterns.encode(newQuotePattern)
    // println(i"\nnewQuotePattern: $newQuotePattern")
    // println(i"\nencoded: $encoded")

    newQuotePattern
  }
}

object QuotesAndSplices {
  import tpd._

  /** Key for mapping from quoted pattern type variable names into their symbol */
  private val TypeVariableKey = new Property.Key[collection.mutable.Map[TypeName, Symbol]]

  /** Get the symbol for the quoted pattern type variable if it exists */
  def getQuotedPatternTypeVariable(name: TypeName)(using Context): Option[Symbol] =
    ctx.property(TypeVariableKey).get.get(name)

    /** Get the symbol for the quoted pattern type variable if it exists */
  def addQuotedPatternTypeVariable(sym: Symbol)(using Context): Unit =
    ctx.property(TypeVariableKey).get.update(sym.name.asTypeName, sym)

  /** Context used to type the contents of a quoted */
  def quotePatternContext()(using Context): Context =
    quoteContext.fresh.setNewScope
      .addMode(Mode.QuotedPattern).retractMode(Mode.Pattern)
      .setProperty(TypeVariableKey, collection.mutable.Map.empty)
}
