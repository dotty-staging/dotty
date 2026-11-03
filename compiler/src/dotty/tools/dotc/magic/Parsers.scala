package dotty.tools
package dotc
package magic

import scala.annotation.tailrec
import scala.annotation.threadUnsafe as tu
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.collection.immutable.BitSet
import util.{SourceFile, SourcePosition, NoSourcePosition, SrcPos}
import Tokens.*
import Scanners.*
import core.*
import Flags.*
import Contexts.*
import Names.*
import NameKinds.{WildcardParamName, QualifiedName}
import NameOps.*
import ast.{Positioned, Trees}
import ast.Trees.*
import ast.untpd
import StdNames.*
import util.Spans.*
import util.chaining.*
import Constants.*
import Symbols.NoSymbol
import Decorators.*
import util.Chars
import rewrites.Rewrites.{overlapsPatch, patch, unpatch}
import reporting.*
import config.Feature
import config.Feature.{sourceVersion, migrateTo3}
import config.SourceVersion.*
import config.{SourceVersion, MigrationVersion}
import Chars.{isWhitespace, isScalaLetter}
import Comments.Comment
import dotty.tools.dotc.core.NameKinds.WildcardParamName

object Parsers {

  import ast.untpd.*

  case class OpInfo(operand: Tree, operator: Ident, offset: Offset)

  enum ParamOwner:
    case Class           // class or trait or enum
    case CaseClass       // case class or enum case
    case Def             // method
    case Type            // type alias or abstract type
    case ExtensionPrefix // extension clause, up to and including extension parameter
    case ExtensionFollow // extension clause, following extension parameter
    case PolyFun         // polymorphic function type or expression

    def isClass = // owner is a class
      this == Class || this == CaseClass
    def takesOnlyUsingClauses = // only using clauses allowed for this owner
      this == ExtensionFollow
    def acceptsVariance =
      this == Class || this == CaseClass
    def acceptsCtxBounds(using Context) =
      this != PolyFun

  end ParamOwner

  def unimplementedExpr(using Context): Select =
    Select(scalaDot(nme.Predef), nme.???)

  def unimplementedType(using Context): Select =
    Select(scalaDot(nme.Predef), nme.???)

  /**
   * Compute the precedence of infix operator `operator`
   */
  def precedence(operator: Name): Int =
    if (operator eq nme.ERROR) -1
    else {
      val firstCh = operator.firstCodePoint
      if isScalaLetter(firstCh) then 1
      else if operator.isOpAssignmentName then 0
      else firstCh match
        case '|' => 2
        case '^' => 3
        case '&' => 4
        case '=' | '!' => 5
        case '<' | '>' => 6
        case ':' => 7
        case '+' | '-' => 8
        case '*' | '/' | '%' => 9
        case _ => 10
    }

  def minPrec: Int = 1
  def maxPrec: Int = 10

  /** Parse given interval in the sourcefile, or the whole source file if the
   *  interval is not defined.
   */
  class Parser(source: SourceFile, startFrom: Offset = 0, limit: Offset = -1)(using Context) {

    val in: Scanner = new Scanner(source, startFrom, limit)
    in.debugTokenStream = false    // uncomment to see the token stream of the standard scanner, but not syntax highlighting

    /* ------------- POSITIONS ------------------------------------------- */

    /** Positions tree.
     *  If `t` does not have a span yet, set its span to the given one.
     */
    def atSpan[T <: Positioned](span: Span)(t: T): T =
      if t.span.isSourceDerived then t
      else t match
        case tree: Tree if tree.isEmpty => t
        case _ => t.withSpan(span.union(t.span))

    def atSpan[T <: Positioned](start: Offset, point: Offset, end: Offset)(t: T): T =
      atSpan(Span(start, end, point))(t)

    /** If the last read offset is strictly greater than `start`, assign tree
     *  the span from `start` to last read offset, with given point.
     *  If the last offset is less than or equal to start, the tree `t` did not
     *  consume any source for its construction. In this case, don't assign a span yet,
     *  but wait for its span to be determined by `setChildSpans` when the
     *  parent node is positioned.
     */
    def atSpan[T <: Positioned](start: Offset, point: Offset)(t: T): T =
      if in.lastOffset > start then atSpan(start, point, in.lastOffset)(t) else t

    def atSpan[T <: Positioned](start: Offset)(t: T): T =
      atSpan(start, start)(t)

    inline def atNameSpan[T <: NameTree](start: Offset)(inline tree: => T): T =
      val backquoted = in.token == BACKQUOTED_IDENT
      atSpan(start, if backquoted then in.offset + 1 else in.offset):
        tree.tap: t =>
          if backquoted && !t.hasAttachment(Backquoted) then
            t.pushAttachment(Backquoted, ())

    def startOffset(t: Positioned): Int =
      if t.span.exists then t.span.start else in.offset

    def pointOffset(t: Positioned): Int =
      if t.span.exists then t.span.point else in.offset

    def endOffset(t: Positioned): Int =
      if t.span.exists then t.span.end else in.lastOffset

    def nameStart: Offset =
      if in.token == BACKQUOTED_IDENT then in.offset + 1 else in.offset

    /** in.offset, except if this is at a new line, in which case `lastOffset` is preferred. */
    def expectedOffset: Int =
      val current = in.sourcePos()
      val last = in.sourcePos(in.lastOffset)
      if (current.line != last.line) in.lastOffset else in.offset

  /* -------------- TOKEN CLASSES ------------------------------------------- */

    def isIdent = in.isIdent
    def isIdent(name: Name) = in.isIdent(name)
    def isBoundName = in.token == IDENTIFIER || in.token == USCORE
    def isNegatedNumber = isIdent(nme.raw.MINUS) && numericLitTokens.contains(in.lookahead.token)
    def isSimpleLiteral = simpleLiteralTokens.contains(in.token) || isNegatedNumber
    def isLiteral = isSimpleLiteral || in.token == INTERPOLATIONID
    def isNumericLit = numericLitTokens contains in.token
    def isDclIntro = dclIntroTokens contains in.token
    def isStatSeqEnd = in.isNestedEnd || in.token == EOF || in.token == RPAREN

    /** Is current token a hard or soft modifier (in modifier position or not)? */
    def isModifier: Boolean = modifierTokens.contains(in.token) || in.isSoftModifier

    def isExprIntro: Boolean =
      canStartExprTokens.contains(in.token)
      && !in.isSoftModifierInModifierPosition

    def isDefIntro: Boolean =
      in.token == AT
      || defIntroTokens.contains(in.token)
      || in.isSoftModifierInModifierPosition

    def isStatSep: Boolean = in.isStatSep

    /* ------------- ERROR HANDLING ------------------------------------------- */
    /** The offset where the last syntax error was reported, or if a skip to a
     *  safepoint occurred afterwards, the offset of the safe point.
     */
    protected var lastErrorOffset : Int = -1

    /** Is offset1 less or equally indented than offset2?
     *  This is the case if the characters between the preceding end-of-line and offset1
     *  are a prefix of the characters between the preceding end-of-line and offset2.
     */
    def isLeqIndented(offset1: Int, offset2: Int): Boolean =
      def recur(idx1: Int, idx2: Int): Boolean =
        idx1 == offset1
        || idx2 < offset2 && source(idx1) == source(idx2) && recur(idx1 + 1, idx2 + 1)
      recur(source.startOfLine(offset1), source.startOfLine(offset2))

    /** Skip on error to next safe point.
     */
    def skip(): Unit =
      in.skip()
      lastErrorOffset = in.offset

    def warning(msg: Message, offset: Int = in.offset): Unit =
      report.warning(msg, source.atSpan(Span(offset)))

    def deprecationWarning(msg: Message, offset: Int = in.offset): Unit =
      report.deprecationWarning(msg, source.atSpan(Span(offset)))

    /** Issue an error at given offset if beyond last error offset
     *  and update lastErrorOffset.
     */
    def syntaxError(msg: Message, offset: Int = in.offset): Unit =
      if offset > lastErrorOffset then
        val length = if offset == in.offset && in.name != null then in.name.nn.show.length else 0
        syntaxError(msg, Span(offset, offset + length))
        lastErrorOffset = in.offset

    /** Issue an error at current offset that input is incomplete */
    def incompleteInputError(msg: Message): Unit =
      if in.offset != lastErrorOffset then
        report.incompleteInputError(msg, source.atSpan(Span(in.offset)))

    /** If at end of file, issue an incompleteInputError.
     *  Otherwise issue a syntax error and skip to next safe point.
     */
    def syntaxErrorOrIncomplete(msg: Message, offset: Int = in.offset): Unit =
      if in.token == EOF then
        incompleteInputError(msg)
      else
        syntaxError(msg, offset)
        skip()

    /** Unconditionally issue an error at given span, without
     *  updating lastErrorOffset.
     */
    def syntaxError(msg: Message, span: Span): Unit =
      report.error(msg, source.atSpan(span))

    def syntaxErrorOrIncomplete(msg: Message, span: Span): Unit =
      if in.token == EOF then
        incompleteInputError(msg)
      else
        syntaxError(msg, span)
        skip()

    def accept(token: Int, help: String): Int =
      val offset = in.offset
      if in.token != token then
        syntaxErrorOrIncomplete(ExpectedTokenButFound(token, in.token, suffix = help))
      if in.token == token then in.nextToken()
      offset

    /** Consume one token of the specified type, or
      * signal an error if it is not there.
      *
      * @return The offset at the start of the token to accept
      */
    def accept(token: Int): Int =
      val offset = in.offset
      if in.token != token then
        syntaxErrorOrIncomplete(ExpectedTokenButFound(token, in.token))
      if in.token == token then in.nextToken()
      offset

    /** Consume one token in the specified set, or
      * signal an error if it is not there.
      *
      * @return The offset at the start of the token to accept
      */
    def acceptOneOf(tokens: Int*): Int =
      val offset = in.offset
      if !tokens.contains(in.token) then
        syntaxErrorOrIncomplete(
          em"""${tokens.map(showToken).mkString(" or ")} expected but ${showToken(in.token)} found""")
      if tokens.contains(in.token) then in.nextToken()
      offset

    /** semi = nl {nl} | `;'
     *  nl  = `\n' // where allowed
     */
    def acceptStatSep(): Unit =
      if in.token == NEWLINE then in.nextToken() else accept(SEMI)

    def acceptIdent(name: Name): Int =
      val offset = in.offset
      if !in.isIdent(name) then
        syntaxErrorOrIncomplete(em"""'$name' expected, but ${showToken(in.token)} found""")
      if in.isIdent(name) then in.nextToken()
      offset

    def errorTermTree(start: Offset): Tree =
      val limit = if in.token == DEDENT then start else in.offset
      atSpan(Span(start, limit)) { unimplementedExpr }

    def errorTypeTree(start: Offset): Tree =
      val limit = if in.token == DEDENT then start else in.offset
      atSpan(Span(start, limit)) { scalaAny }

/* ---- WRAPS ---- */

    // We just need the Type and Pattern bits
    private var currentMode = Mode.None
    private def withMode[T](mode: Mode)(body: => T): T =
      val saved = currentMode
      currentMode = mode
      try body
      finally currentMode = saved

/* ---------- TREE CONSTRUCTION ------------------------------------------- */

    /** Convert tree to formal parameter list
    */
    def convertToParams(tree: Tree): ParamClause =
      val mods = if in.token == CTXARROW
        then Modifiers(Given)
        else EmptyModifiers
      tree match
        case Parens(t) => convertToParam(t, mods) :: Nil
        case Tuple(ts) => ts.map(convertToParam(_, mods))
        case SeqLiteral(ts, _) => ts.map(convertToTypeParam)
        case t => convertToParam(t, mods) :: Nil

    /** Convert tree to formal value parameter
    */
    def convertToParam(tree: Tree, mods: Modifiers): ValDef =
      def fail() =
        syntaxError(em"not a legal formal parameter for a function literal", tree.span)
        makeParameter(nme.ERROR, tree, mods)
      tree match
        case param: ValDef =>
          param.withMods(param.mods | mods.flags)
        case id @ Ident(name) =>
          makeParameter(name.asTermName, TypeTree(), mods, isBackquoted = isBackquoted(id)).withSpan(tree.span)
        // the following three cases are needed only for 2.x parameters without enclosing parentheses
        case Typed(_, tpt: TypeBoundsTree) =>
          fail()
        case Typed(id @ Ident(name), tpt) =>
          makeParameter(name.asTermName, tpt, mods, isBackquoted = isBackquoted(id)).withSpan(tree.span)
        case _ =>
          fail()

    /** Convert tree to type parameter */
    def convertToTypeParam(tree: Tree): TypeDef =
      def makeTypeParameter(name: TypeName) =
        cpy.TypeDef(tree)(name, TypeBoundsTree(EmptyTree, EmptyTree)).withFlags(Param)
      tree match
        case td: TypeDef => td
        case id: Ident => makeTypeParameter(id.name.toTypeName)
        case _ =>
          syntaxError(em"not a legal type parameter for a polymorphic function literal", tree.span)
          makeTypeParameter(tpnme.ERROR)

    /** Convert context type list to synthetic type parameters */
    def typesToParams(tps: List[Tree], paramOwner: ParamOwner, numLeadParams: Int, impliedMods: Modifiers): List[ValDef] =
      var counter = numLeadParams
      def nextIdx = { counter += 1; counter }
      val paramFlags = if paramOwner.isClass then LocalParamAccessor else Param
      tps.map(makeSyntheticParameter(nextIdx, _, paramFlags | Synthetic | impliedMods.flags))

    /** The type CapSet^{refs} */
    def concreteCapsType(refs: List[Tree]): Tree =
      makeRetaining(Select(scalaDot(nme.caps), tpnme.CapSet), refs, tpnme.retains)

    /** Checks that tuples don't contain a parameter. */
    def checkNonParamTuple(t: Tree) = t match
      case Tuple(ts) => ts.collectFirst:
        case param: ValDef =>
          syntaxError(em"invalid parameter definition syntax in tuple value", param.span)
      case _ =>

    /** Convert (qual)ident to type identifier
     */
    def convertToTypeId(tree: Tree): Tree = tree match {
      case id @ Ident(name) =>
        cpy.Ident(id)(name.toTypeName)
      case id @ Select(qual, name) =>
        cpy.Select(id)(qual, name.toTypeName)
      case _ =>
        syntaxError(IdentifierExpected(tree.show), tree.span)
        tree
    }

    def identFromKW(): Ident =
      val name = in.name.nn
      atSpan(in.skipToken())(Ident(name))

/* --------------- PLACEHOLDERS ------------------------------------------- */

    /** The implicit parameters introduced by `_` in the current expression.
     *  Parameters appear in reverse order.
     */
    var placeholderParams: List[ValDef] = Nil

    def checkNoEscapingPlaceholders[T](op: => T): T =
      val savedPlaceholderParams = placeholderParams
      val savedLanguageImportContext = in.languageImportContext
      placeholderParams = Nil
      try op
      finally
        placeholderParams match
          case vd :: _ => syntaxError(UnboundPlaceholderParameter(), vd.span)
          case _ =>
        placeholderParams = savedPlaceholderParams
        in.languageImportContext = savedLanguageImportContext

    def isLastPlaceholder(t: Tree): Boolean = t match {
      case Ident(name1) => placeholderParams.nonEmpty && name1 == placeholderParams.head.name
      case Typed(t1, _) => isLastPlaceholder(t1)
      case Annotated(t1, _) => isLastPlaceholder(t1)
      case Parens(t1) => isLastPlaceholder(t1)
      case _ => false
    }

    def isTypeBounds(t: Tree): Boolean = t match {
      case t: TypeBoundsTree => true
      case Parens(t1) => isTypeBounds(t1)
      case _ => false
    }

/* -------- COMBINATORS -------------------------------------------------------- */

    def enclosed[T](tok: Token, body: => T): T =
      accept(tok)
      try body finally accept(tok + 1)

    /** Same as enclosed, but if closing token is missing, add `,` to the expected tokens
     *  in the error message, provided the next token could have followed a `,`.
     */
    def enclosedWithCommas[T](tok: Token)(body: => T): T =
      accept(tok)
      val closing = tok + 1
      val isEmpty = in.token == closing
      val ts = body
      if in.token != closing then
        val followComma =
          if tok == LPAREN then canStartExprTokens else canStartTypeTokens
        val prefix = if !isEmpty && followComma.contains(in.token) then "',' or " else ""
        syntaxErrorOrIncomplete(ExpectedTokenButFound(closing, in.token, prefix))
      if in.token == closing then in.nextToken()
      ts

    def inParens[T](body: => T): T = enclosed(LPAREN, body)
    def inBraces[T](body: => T): T = enclosed(LBRACE, body)
    def inBrackets[T](body: => T): T = enclosed(LBRACKET, body)
    def indented[T](body: => T): T = enclosed(INDENT, body)

    def inParensWithCommas[T](body: => T): T = enclosedWithCommas(LPAREN)(body)
    def inBracketsWithCommas[T](body: => T): T = enclosedWithCommas(LBRACKET)(body)
    def inBracesWithCommas[T](body: => T): T = enclosedWithCommas(LBRACE)(body)

    def inBracesOrIndented[T](body: => T): T =
      if in.token == INDENT then indented(body)
      else inBraces(body)

    def maybeIndented[T](body: => T): T =
      if in.token == INDENT then indented(body) else body

    def inDefScopeBraces[T](body: => T): T =
      inBracesOrIndented(body)

    /** <part> {`,` <part>} */
    def commaSeparated[T](part: () => T): List[T] =
      in.currentRegion.withCommasExpected {
        commaSeparatedRest(part(), part)
      }

    /** {`,` <part>}
     *
     *  currentRegion.commasExpected has to be set separately.
     */
    def commaSeparatedRest[T](leading: T, part: () => T): List[T] =
      if in.token == COMMA then
        val ts = new ListBuffer[T] += leading
        while in.token == COMMA do
          in.nextToken()
          ts += part()
        ts.toList
      else leading :: Nil

    def maybeNamed(op: () => Tree, isType: Boolean = false): () => Tree = () =>
      if in.token == IDENTIFIER && in.lookahead.token == EQUALS then named(op, isType)()
      else op()

    def named(op: () => Tree, isType: Boolean = false): () => Tree = () =>
      atSpan(in.offset):
        var name: Name = ident()
        if isType then name = name.toTypeName
        accept(EQUALS)
        NamedArg(name, op())

    def inRegion[T](f: Region => Region)(op: => T): T =
      val cur = in.currentRegion
      in.currentRegion = f(cur)
      try op finally in.currentRegion = cur

    /** Check that this is not the start of a statement that's indented relative to the current region.
     */
    def checkNextNotIndented(): Unit =
      if in.token == NEWLINE && in.next.token != STRINGLIT // admit indented docstrings
      then
        val nextIndentWidth = in.indentWidth(in.next.offset)
        if in.currentRegion.indentWidth < nextIndentWidth && in.currentRegion.closedBy == DEDENT then
          warning(IndentationWarning(missing = COLON), in.next.offset)

/* --------- LOOKAHEAD --------------------------------------- */

    /** Is current ident a `*`, and is it followed by a `)`, `,`, or `EOF`?
     *  EOF is not syntactically valid, but we need to include it here for error
     *  recovery.
     */
    def followingIsVararg(): Boolean =
      in.isIdent(nme.raw.STAR) && varargFollowers.contains(in.lookahead.token)

    /** Is current ident a `*`, and is it potentially followed by an infix expression
     *  or type on the same line?
     */
    def followingIsPrefixSpread(): Boolean =
      in.token == IDENTIFIER
          && in.name.nn == nme.raw.STAR
          && canStartInfixExprOrTypeTokens.contains(in.lookahead.token)
          && in.lookahead.lineOffset < 0

    /** Is the current token sequence necessarily a type parameter of a polyfunction
     *  and not an expression in a list?
     *  This recognizes patterns that start with
     *    '@' | ident ('<:' | '>:' | '^' FOLLOW)
     *  where FOLLOW is one of '<:' | '>:' | ',' | ']'.
     *  Note: we need not consider ident followed by ':' since polyfunction type
     *  parameters can't have context bounds. This is important since otherwise we'd
     *  have an ambiguity with type ascriptions in list expressions. We also don't consider
     *  EOF in the follow set since an incomplete line "[A, B" is assumed to be a list expression.
     */
    def followingIsTypeParam(): Boolean =
      in.token == AT
      || in.isIdent
          && {
            if in.lookahead.isIdent(nme.UPARROW) then
              val lookahead = in.LookaheadScanner()
              lookahead.nextToken() // skip ident
              lookahead.nextToken() // skip ^
              lookahead.token == SUBTYPE
              || lookahead.token == SUPERTYPE
              || lookahead.token == COMMA
              || lookahead.token == RBRACKET
            else
              in.lookahead.token == SUBTYPE
              || in.lookahead.token == SUPERTYPE
          }

  /* --------- OPERAND/OPERATOR STACK --------------------------------------- */

    var opStack: List[OpInfo] = Nil

    def checkAssoc(offset: Token, op1: Name, op2: Name | Null, op2LeftAssoc: Boolean): Unit =
      if (op1.isRightAssocOperatorName == op2LeftAssoc)
        syntaxError(MixedLeftAndRightAssociativeOps(op1, op2, op2LeftAssoc), offset)

    def reduceStack(base: List[OpInfo], top: Tree, prec: Int, leftAssoc: Boolean, op2: Name | Null): Tree =
      if opStack != base && precedence(opStack.head.operator.name) == prec then
        checkAssoc(opStack.head.offset, opStack.head.operator.name, op2, leftAssoc)
      def recur(top: Tree): Tree =
        if opStack == base then top
        else
          val opInfo = opStack.head
          val opPrec = precedence(opInfo.operator.name)
          if prec < opPrec || leftAssoc && prec == opPrec then
            opStack = opStack.tail
            recur:
              atSpan(opInfo.operator.span `union` opInfo.operand.span `union` top.span):
                InfixOp(opInfo.operand, opInfo.operator, top)
          else top
      recur(top)

    /**   operand { op operand }
     *
     *  respecting rules of associativity and precedence.
     */
    def infixOps(first: Tree, operand: () => Tree): Tree =
      val base = opStack

      def recur(top: Tree): Tree =
        if in.isOperator && !followingIsVararg() then
          val op = if currentMode.is(Mode.Type) then typeIdent() else termIdent()
          val top1 = reduceStack(base, top, precedence(op.name), !op.name.isRightAssocOperatorName, op.name)
          opStack = OpInfo(top1, op, in.offset) :: opStack
          recur(operand())
        else
          reduceStack(base, top, minPrec, leftAssoc = true, null)

      recur(first)
    end infixOps

/* -------- IDENTIFIERS AND LITERALS ------------------------------------------- */

    /** Accept identifier and return its name as a term name. */
    def plainid(): TermName =
      if in.token == BACKQUOTED_IDENT then
        syntaxError(em"No backticked identifier allowed here")
      ident()

    def ident(): TermName =
      if isIdent then
        val name = in.name.nn
        in.nextToken()
        name
      else
        syntaxErrorOrIncomplete(ExpectedTokenButFound(IDENTIFIER, in.token))
        nme.ERROR

    /** Accept identifier and return Ident with its name as a term name. */
    def termIdent(): Ident =
      makeIdent(in.token, in.offset, ident())

    def plainTermIdent(): Ident =
      makeIdent(in.token, in.offset, plainid())

    /** Accept identifier and return Ident with its name as a type name. */
    def typeIdent(): Ident =
      makeIdent(in.token, in.offset, ident().toTypeName)

    private def makeIdent(tok: Token, offset: Offset, name: Name) =
      val tree = Ident(name)
      if tok == BACKQUOTED_IDENT then tree.pushAttachment(Backquoted, ())

      // Make sure that even trees with parsing errors have an offset that is within the offset
      val errorOffset = offset min (in.lastOffset - 1)
      if (tree.name == nme.ERROR && tree.span == NoSpan) tree.withSpan(Span(errorOffset, errorOffset))
      else atSpan(offset)(tree)

    def wildcardIdent(isType: Boolean = false): Ident =
      atSpan(in.skipToken()):
        Ident(if isType then tpnme.WILDCARD else nme.WILDCARD)

    def selector(t: Tree): Tree =
      atSpan(startOffset(t), in.offset) { Select(t, ident()) }

    /** DotSelectors ::= { `.' id } */
    def dotSelectors(t: Tree): Tree =
      if (in.token == DOT) { in.nextToken(); dotSelectors(selector(t)) }
      else t

    /** ClassQualifier ::= `[' id `]' */
    def classQualifierOpt(): Ident =
      if in.token == LBRACKET then inBrackets(atSpan(in.offset) { typeIdent() })
      else EmptyTypeIdent

    /** SimpleRef  ::= ['super' [ClassQualifier] '.'] id
     */
    def simpleRef(): Tree =
      if in.token == SUPER then
        val sup = atSpan(in.skipToken()):
          Super(This(EmptyTypeIdent), classQualifierOpt())
        accept(DOT)
        selector(sup)
      else termIdent()

    /** SimpleRefOrSelf  ::=  SimpleRef  |  Self
     *  Self             ::=  'self' [ClassQualifier]
     */
    def simpleRefOrSelf(): Tree =
      if in.token == SELF then
        atSpan(in.skipToken()):
          This(classQualifierOpt())
      else
        simpleRef()

    /** PathRef         ::=  SimpleRef {'.' id}
     */
    def pathRef(): Tree = dotSelectors(simpleRef())

    /** Singleton    ::=  (SimpleRef | Self) {'.' id}
     */
    def singleton(): Tree = dotSelectors(simpleRefOrSelf())

    /**  Path             ::=  id
     *                      |  'self' [ClassQualifier] '.' id
     *                      |  'super' '.' id
     *                      |  Singleton '.' id
     */
    def qualId(): Tree = dotSelectors(termIdent())

    /** BoundName        ::=  plainid | '_'
     */
    def boundName(): TermName =
      if in.token == USCORE then
        in.nextToken()
        WildcardParamName.fresh()
      else plainid()

    /** Literal     ::=  [‘-’] numericLiteral
     *                |  stringLiteral
     *                |  'True'  |  'False'  |  'None'
     *
     *  @param start   The offset of a preceding `-' sign, if any.
     *                 If the literal is not negated, start == in.offset.
     */
    def literal(inSingleton: Boolean = false): Tree =
      val start = in.offset
      val isNegated = in.isIdent(nme.raw.MINUS)
      if isNegated then in.nextToken()

      def digits0 = in.removeNumberSeparators(in.strVal.nn)
      def digits = if isNegated then "-" + digits0 else digits0

      def num(kind: NumberKind): Tree =
        val d = digits
        if d.isEmpty then
          syntaxError(IllegalLiteral(), start)
          Literal(Constant.fromValue(null))
        else
          Number(d, kind)

      if !inSingleton then
        // Return generic Number representation for numeric literals unless we are
        // in a singleton context where no expected type is available.
        in.token match
          case INTLIT  => return num(NumberKind.Whole(in.base))
          case DECILIT => return num(NumberKind.Decimal)
          case EXPOLIT => return num(NumberKind.Floating)
          case _ =>

      import scala.util.FromDigits.*

      def lit[T](value: T)(using Constant.ValueToConstant[T]): Tree =
        Literal(Constant.fromValue(value))

      try in.token match
        case INTLIT              => lit(intFromDigits(digits, in.base))
        case DECILIT | EXPOLIT   => lit(doubleFromDigits(digits))
        case STRINGLIT           => lit(trim(in.strVal.nn, start + in.delimCount))
        case TRUE                => lit(true)
        case FALSE               => lit(false)
        case NONE                => lit(null)
      catch case ex: FromDigitsException =>
        syntaxErrorOrIncomplete(ex.getMessage.toMessage)
        lit(null)
    end literal

    def interpolatedString(inPattern: Boolean): Tree =
      val start = in.offset
      val segmentBuf = new ArrayBuffer[Tree]
      val interpolator = in.name.nn
      val startOffset = in.charOffset
      in.nextToken()

      def interpolationExpr() =
        atSpan(in.offset):
          inBraces:
            if inPattern then pattern() else block()

      var offsetCorrection = in.delimCount
      var needsTrim: Boolean = false

      def strLit() =
        val start = in.offset + offsetCorrection
        val value = in.strVal.nn
        if offsetCorrection != 0 then // we see the first segment
          needsTrim = trim.needsTrim(value, start)
        atSpan(start, start, start + value.length):
          Literal(Constant(value))

      while in.token == STRINGPART do
        segmentBuf += Thicket(strLit(), interpolationExpr())
        offsetCorrection = 0

      if in.token == STRINGLIT then
        val dedentWidth = trim.lastIndent(in.strVal.nn)
        segmentBuf += strLit()
        if needsTrim then
          for i <- 0 until segmentBuf.length do
            segmentBuf(i) = trim(segmentBuf(i), dedentWidth,
              isFirst = i == 0, isLast = i == segmentBuf.length - 1)

      atSpan(start):
        InterpolatedString(interpolator, segmentBuf.toList)
    end interpolatedString

    /** Trimming multiline strings */
    object trim:

      private case class Cut(offset: Int, length: Int)

      private def shorten(cs: String, cuts: List[Cut]): String =
        val totalCutSize = cuts.map(_.length).sum
        if totalCutSize > cs.length then
          "" // happens for empty '''-enclosed literals since the \n is counted twice
        else
          val target = new java.lang.StringBuilder(cs.length - totalCutSize)
          def recur(cuts: List[Cut], fromIdx: Int, toIdx: Int): Unit = cuts match
            case Nil =>
              target.append(cs, fromIdx, cs.length)
            case Cut(offset, length) :: cuts1 =>
              val len = offset - fromIdx
              target.append(cs, fromIdx, offset)
              recur(cuts1, offset + length, toIdx + len)
          recur(cuts, 0, 0)
          target.toString

      /** Trim the start of a '''-literal, that ends up to and including the
       *  first \n, provided all characters are whitespace.
       */
      private def trimStart(cs: String, strOffset: Int): List[Cut] =
        var i = 0
        while i < cs.length && isWhitespace(cs(i)) do i += 1
        if i < cs.length && cs(i) == Chars.LF then
          Cut(0, i + 1) :: Nil
        else
          Nil

      /** Trim the end of a '''-literal, up to and including the last \n.
       *  This must be all whitespace.
       */
      private def trimEnd(cs: String, strOffset: Int): List[Cut] =
        var i = cs.length - 1
        while i >= 0 && isWhitespace(cs(i)) do i -= 1
        if i >= 0 && cs(i) == Chars.LF then
          Cut(i, cs.length - i) :: Nil
        else
          syntaxError(
            em"Last line of dedented string literal must contain only whitespace before closing delimiter",
            strOffset + i)
          Nil

      /** Trim the IndentWidth prefix from all starts of lines.
       *  Error if a line does not start with at least IndentWidth.
       */
      private def trimLeft(cs: String, width: IndentWidth, strOffset: Int): List[Cut] =
        def checkCut(cut: Cut): Boolean =
          if cut.length < 0 then
            var i = cut.offset
            while isWhitespace(cs(i)) do i += 1
            if cs(i) != Chars.LF then // lines consisting only of whitespace are ignored
              syntaxError(
                em"""Line in dedented string literal must be indented at least as much as the closing delimiter
                    |This indent   : ${in.indentWidth(i, cs)}
                    |Closing indent: $width""",
                strOffset + i)
          cut.length > 0
        (0 until cs.length)
          .filter(i => cs(i) == Chars.LF)
          .map(i => Cut(i + 1, width.advance(cs, i + 1)))
          .filter(checkCut)
          .toList

      /** The indentation width of the last line in `cs` (i.e. what comes before the closing `'''`).
       */
      def lastIndent(cs: String): IndentWidth =
        in.indentWidth(cs.length, cs)

      private def trimAll(cs: String, width: IndentWidth, strOffset: Int,
                          isFirst: Boolean, isLast: Boolean): String =
        val startCuts = if isFirst then trimStart(cs, strOffset) else Nil
        var leftCuts = trimLeft(cs, width, strOffset)
        if isLast then
          leftCuts = leftCuts.dropRight(1) // last trimLeft overlaps with trimEnd
        val endCuts = if isLast then trimEnd(cs, strOffset) else Nil
        shorten(cs, startCuts ++ leftCuts ++ endCuts)

      /** A string needs trimming it it has only whitespace between the starting
       *  quote and the next line.
       */
      def needsTrim(str: String, strOffset: Int): Boolean =
        trimStart(str, strOffset).nonEmpty

      /** Trim a non-interpolated string `str`.
       *  The string without leading quotes starts at `strOffset`.
       */
      def apply(str: String, strOffset: Int): String =
        trimAll(str, lastIndent(str), strOffset, isFirst = true, isLast = true)

      /** Trim part of an interpolated string literal */
      def apply(tree: Tree, width: IndentWidth, isFirst: Boolean, isLast: Boolean): Tree = tree match
        case Thicket(lit :: rest) =>
          Thicket(apply(lit, width, isFirst, isLast) :: rest)
        case Literal(Constant(str: String)) =>
          val trimmed = trimAll(str, width, startOffset(tree), isFirst, isLast)
          cpy.Literal(tree)(Constant(trimmed))
    end trim

/* ------------- NEW LINES ------------------------------------------------- */

    def newLineOpt(): Unit =
      if in.token == NEWLINE then in.nextToken()

    def newLineOptWhenFollowedBy(token: Int): Unit =
      // note: next is defined here because current == NEWLINE
      if in.token == NEWLINE && in.next.token == token then in.nextToken()

/* ------------- TYPES ------------------------------------------------------ */

    /** Type            ::=  FunType
     *                    |  MatchType
     *                    |  InfixType
     *  FunType         ::=  ParamsOfFunType (Arrow | ContextArrow) Type
     *                    |  TypeParams Arrow Type
     *  MatchType       ::=  `match` InfixType ':' TypeCaseClause { TypeCaseClause }
     *  ParamsOfFunType ::=  InfixType
     *                    |  ‘(’ [ ContextTypes | {FunParam}_',' ] ‘)’
     */
    def typ(): Tree =
      val start = in.offset

      def functionRest(params: List[Tree]): Tree =
        atSpan(start, in.offset):
          val paramSpan = Span(start, in.lastOffset)
          var funMods = in.token match
            case ARROW => Modifiers(Impure)
            case CTXARROW => Modifiers(Impure | Given)
            case THINCTXARROW => Modifiers(Given)
            case THINARROW => EmptyModifiers
          in.nextToken()
          val resultType =
            if funMods.is(Impure) then typ()
            else capturesAndResult(typ)
          if funMods.flags.isEmpty then Function(params, resultType)
          else
            if funMods.is(Given) then
              funMods &~= Given
              syntaxError(em"context function types require at least one parameter", paramSpan)
            FunctionWithMods(params, resultType, funMods)

      def typeRest(t: Tree) =
        if in.isArrow then functionRest(t :: Nil) else t

      def convertToElem(t: Tree): Tree = t match
        case ByNameTypeTree(t1) =>
          syntaxError(ByNameParameterNotSupported(t), t.span)
          t1
        case t @ ValDef(name, tpt, _) =>
          if t.mods.is(Erased) then
            report.error(ErasedTypesCanOnlyBeFunctionTypes(), t.srcPos)
          NamedArg(name, convertToElem(tpt)).withSpan(t.span)
        case _ => t

      if in.token == LPAREN then
        in.nextToken()
        if in.token == RPAREN then
          in.nextToken()
          functionRest(Nil)
        else
          val paramStart = in.offset
          def paramMods(): Modifiers =
            if in.isIdent(nme.erased) then addModifier(EmptyModifiers) else EmptyModifiers
          val leadingMods = paramMods()
          val args =
            in.currentRegion.withCommasExpected:
              paramType() match
                case Ident(name) if name != tpnme.WILDCARD && in.token == COLON =>
                  def funParam(start: Offset) =
                    atSpan(start):
                      val mods = paramMods()
                      funParamRest(in.offset, ident(), mods)
                  commaSeparatedRest(
                    funParamRest(paramStart, name.toTermName, leadingMods),
                    () => funParam(in.offset))
                case t =>
                  if leadingMods.is(Erased) then
                    report.error(em"Erased function parameter must be named", leadingMods.mods.head.srcPos)
                  commaSeparatedRest(t, paramType)
          accept(RPAREN)
          if in.isSimpleArrow then
            functionRest(args)
          else
            val tuple = atSpan(start):
              makeTupleOrParens(args.mapConserve(convertToElem))
            typeRest:
              infixTypeRest:
                capTypeRest:
                  annotTypeRest:
                    simpleTypeRest(tuple)
      else if in.token == LBRACKET then
        val start = in.offset
        val tparams = typeParamsOpt(ParamOwner.Type)
        val arrowOffset = accept(THINARROW)
        val body = typ()
        atSpan(start, arrowOffset):
          PolyFunction(tparams, body)
      else if in.token == MATCH then
        atSpan(in.skipToken()):
          val selector = inRegion(BeforeColon)(infixType())
          accept(COLON)
          MatchTypeTree(EmptyTree, selector, caseClauses(typeCaseClause))
      else
        typeRest(infixType())
    end typ

    /** FunParam   ::= [`erased`] Param */
    def funParamRest(start: Offset, name: TermName, mods: Modifiers = EmptyModifiers): ValDef =
      atSpan(start):
        accept(COLON)
        makeParameter(name, typ(), mods)

     def typeCaseClause(): CaseDef =
      atSpan(accept(CASE)):
        val pat =
          inRegion(BeforeColon):
            if in.token == USCORE then
              wildcardIdent(isType = true)
            else
              withMode(currentMode | Mode.Pattern):
                infixType()
        accept(COLON)
        CaseDef(pat, EmptyTree, typ())

    /** InfixType  ::=  CapType {op CapType}
     */
    def infixType(): Tree =
      infixTypeRest(capType())

    def infixTypeRest(t: Tree): Tree =
      infixOps(t, capType)

    /** CapType   ::=  AnnotType [`^` CapSet]
     */
    val capType: () => Tree = () =>
      capTypeRest(annotType())

    def capTypeRest(t: Tree): Tree =
      if in.isIdent(nme.UPARROW) then
        atSpan(startOffset(t)):
          in.nextToken()
          if in.token == LBRACE
          then makeRetaining(t, capSet(), tpnme.retains)
          else makeRetaining(t, Nil, tpnme.retainsCap)
      else
        t

    /** AnnotType ::= SimpleType {Annotation}
     */
    def annotType(): Tree = annotTypeRest(simpleType())

    def annotTypeRest(t: Tree): Tree =
      if in.token == AT then
        annotTypeRest:
          atSpan(startOffset(t)):
            Annotated(t, annot())
      else t

    /**  SimpleType       ::=  PathRef [TypeArgs]
     *                      |  ‘(’ [Types] ‘)’
     *                      |  ‘(’ NameAndType {‘,’ NameAndType} ‘)’
     *                      |  '{' Singleton | Literal | Refined '}'
     *                      |  SimpleType '?'
     *                      |  INDENT Type DEDENT
     */
    def simpleType(): Tree =
      val t = in.token match
        case IDENTIFIER | BACKQUOTED_IDENT | SELF | SUPER =>
          withMode(currentMode | Mode.Type):
             typeArgsOpt(convertToTypeId(pathRef()))
        case LPAREN =>
          atSpan(in.offset):
            makeTupleOrParens:
              inParensWithCommas:
                if in.isIdent && in.lookahead.token == COLON
                then commaSeparated(nameAndType)
                else commaSeparated(typ)
        case LBRACE =>
          inBraces:
            if isSimpleLiteral then
              SingletonTypeTree(literal(inSingleton = true))
            else if isBoundName && in.lookahead.token == COLON then
              refined()
            else
              SingletonTypeTree(singleton())
        case INDENT =>
          indented(typ())
        case _ =>
          val start = in.lastOffset
          syntaxErrorOrIncomplete(
            em"Type expected but ${tokenString(in.token)} found", expectedOffset)
          errorTypeTree(start)
      simpleTypeRest(t)

    def simpleTypeRest(t: Tree): Tree =
      if in.isIdent(nme.?) && canStartTypeTokens.contains(in.lookahead.token) then
        atSpan(startOffset(t)):
          PostfixOp(t, typeIdent())
      else  t

    /** Refined      ::=  BoundName ':' Type 'with' Refinements
     *  Refinements  ::=  Dcl | Dcls
     *  Dcls         ::=  ':' INDENT {Dcl}_semi DEDENT
     */
    def refined(): Tree =
      val id = if in.token == USCORE then wildcardIdent() else termIdent()
      accept(COLON)
      val tpt = typ()
      val prefix = if id.name == nme.WILDCARD then tpt else Typed(id, tpt)
      accept(WITH)
      val refinements =
        if in.token == COLON then
          in.nextToken()
          indented(statSeq(refineDcl, "refinement"))
        else
          val buf = new ListBuffer[Tree]
          refineDcl(buf)
          buf.toList
      RefinedTypeTree(prefix, refinements)

    def refineDcl(stats: ListBuffer[Tree]): Unit =
      defOrDcl(in.offset, EmptyModifiers) match
        case tree: DefDef if tree.termParamss.nestedExists(!_.rhs.isEmpty) =>
          syntaxError(em"refinement cannot have default arguments")
        case tree: ValOrDefDef =>
          if tree.rhs.isEmpty then stats += tree
          else syntaxError(em"refinement cannot have a right-hand side")
        case tree: TypeDef =>
          if !tree.isClassDef then stats += tree
          else syntaxError(em"refinement cannot be a class or trait")
        case _ =>
          syntaxError(em"this kind of definition cannot be a refinement")

    /** TypeArgs   ::=  ‘[’ TypeArg {‘,’ TypeArg} ‘]’ */
    def typeArgsOpt(t: Tree): Tree =
      if in.token == LBRACKET then
        atSpan(startOffset(t)):
          inBracketsWithCommas:
            val args = commaSeparated(typeArg)
            if currentMode.is(Mode.Type)
            then AppliedTypeTree(t, args)
            else TypeApply(t, args)
      else
        t

    /** TypeArg        ::=  [id '='] (TypeOrCapSet  |  (‘?’ | '_') TypeBounds)
     *  (_ TypeBounds only in type patterns)
     */
    val unnamedTypeArg: () => Tree = () =>
      if in.token == USCORE then
        if !currentMode.is(Mode.Type | Mode.Pattern) then
          syntaxError(em"`_` can only be used in a type that occurs in a pattern")
        val start = in.skipToken()
        typeBounds().withSpan(Span(start, in.lastOffset, start))
      else if isIdent(nme.?) then
        if !currentMode.is(Mode.Type) then
          syntaxError(em"`?` can only be used in a type")
        val start = in.skipToken()
        typeBounds().withSpan(Span(start, in.lastOffset, start))
      else
        typeOrCapSet()

    val typeArg: () => Tree = maybeNamed(unnamedTypeArg, isType = true): @unchecked // silence initialization error

    /** TypeOrCapSet      ::=  Type  |  CapSet */
    def typeOrCapSet(): Tree =
      if in.token == LBRACE then concreteCapsType(capSet()) else typ()

    /** TypeBounds ::= [`>:' TypeOrCapSet ] [`<:' TypeOrCapSet ]
     */
    def typeBounds(): TypeBoundsTree =
      def bound(tok: Int): Tree =
        if in.token == tok then
          in.nextToken()
          typeOrCapSet()
        else EmptyTree
      atSpan(in.offset):
        TypeBoundsTree(bound(SUPERTYPE), bound(SUBTYPE))

    def nameAndType() =
      atSpan(in.offset):
        val name = ident()
        accept(COLON)
        NamedArg(name, typ())

    /**  CapSet ::=  `{` CapRef {`,` CaptRef} `}`
     */
    def capSet(): List[Tree] =
      inBraces:
        if in.token == RBRACE then Nil else commaSeparated(capRef)

    /** CapRef        ::=  (SimpleRef | Self) [OnlyFilter] {ExcludeFilter} ['.' 'rd']
     *                  |  'rd'
     *  OnlyFilter    ::=  `.` `only` `[` QualId `]`
     *  ExcludeFilter ::=  `.` `excclude` `[` QualId `]`
     */
    def capRef(): Tree =
      def restrictedOpt(ref: Tree): Tree =
        if in.token == DOT && in.lookahead.isIdent(nme.only) then
          atSpan(startOffset(ref)):
            in.nextToken()
            in.nextToken()
            Annotated(ref, makeOnlyAnnot(inBrackets(convertToTypeId(qualId()))))
        else ref

      def exceptOpt(ref: Tree): Tree =
        if in.token == DOT && in.lookahead.isIdent(nme.exclude) then
          val ref1 = atSpan(startOffset(ref)):
            in.nextToken()
            in.nextToken()
            Annotated(ref, makeExceptAnnot(inBrackets(convertToTypeId(qualId()))))
          exceptOpt(ref1)
        else ref

      def readOnlyOpt(ref: Tree): Tree =
        if in.token == DOT && in.lookahead.isIdent(nme.rd) then
          atSpan(startOffset(ref)):
            in.nextToken()
            in.nextToken()
            Annotated(ref, makeReadOnlyAnnot())
        else ref

      def recur(ref: Tree): Tree =
        val ref1 = readOnlyOpt(exceptOpt(restrictedOpt(ref)))
        if (ref1 eq ref) && in.token == DOT then
          in.nextToken()
          recur(selector(ref))
        else ref1

      recur(simpleRefOrSelf())
    end capRef

    def capturesAndResult(core: () => Tree): Tree =
      if in.token == LBRACE && canStartCapSetContentsTokens.contains(in.lookahead.token)
      then CapturesAndResult(capSet(), core())
      else core()

    /** TypedOpt ::= [':' Type]
     */
    def typedOpt(): Tree =
      if in.token == COLON then
        in.nextToken()
        typ()
      else TypeTree().withSpan(Span(in.lastOffset))

/* ----------- EXPRESSIONS ------------------------------------------------ */

    /** SuiteExpr         ::=  Suite
     *                      |  Expr
     */
    def suiteExpr(): Tree =
      if in.token == COLON && !in.currentRegion.isClosedBy(RPAREN)
      then suite()
      else expr()

    /** Suite             ::=  `:` Expr                     (if to end of line)
     *                      |  `:` {ParamPrefix} INDENT Block DEDENT
     */
    def suite(): Tree =
      atSpan(accept(COLON)):
        if in.token == INDENT then indented(block())
        else inRegion(InSuite)(expr())

    /** Expr        ::=  ParamPrefix Expr
     *                |  Expr1
     *  ParamPrefix ::=  FunParams (‘=>’ | ‘?=>’)
     *                |  TypeParams ‘=>’
     *  FunParams   ::=  ‘(’ {Binding}_‘,’ ‘)’
     *                |  BoundName
     */
    def expr(): Tree =
      val start = in.offset
      val saved = placeholderParams
      placeholderParams = Nil

      def wrapPlaceholders(t: Tree) =
        try
          if placeholderParams.isEmpty then t
          else
            val (erasedParams, otherParams) = placeholderParams.partition(_.mods.is(Erased))
              // erased placeholders come from named definitions created in exprsInParensOrBindings.
              // They must be followed by a function arrow, so any such placeholders shoing
              // up here are errors.
            for p <- erasedParams do syntaxError(BadErased(), p.span)
            if otherParams.isEmpty then t
            else new WildcardFunction(otherParams.reverse, t)
        finally placeholderParams = saved

      val t = expr1()
      if in.isFatArrow then
        placeholderParams = Nil // don't interpret `_' to the left of `=>` as placeholder
        val params = convertToParams(t)
        if in.token == CTXARROW && params.isEmpty then // TODO: Move to desugar
          syntaxError(em"context function literals require at least one formal parameter", Span(start, in.lastOffset))
        val isTypeParams = params match
          case (_: TypeDef) :: _ => true
          case _ => false
        val arrowOffset = in.skipToken()
        wrapPlaceholders:
          atSpan(start, arrowOffset):
            val body = expr()
            if isTypeParams then
              def checkFunction(tree: Tree): Unit = tree match
                case Parens(tree1) => checkFunction(tree1)
                case Block(Nil, tree1) => checkFunction(tree1)
                case t: Function =>
                case _ =>
                  syntaxError(em"Implementation restriction: polymorphic function literals must have a value parameter", arrowOffset)
              checkFunction(body)
              PolyFunction(params, body)
            else
              Function(params, body)
      else if isLastPlaceholder(t) then
        placeholderParams = placeholderParams ::: saved
        t
      else
        wrapPlaceholders(t)
    end expr

    /** Expr1     ::=  [‘inline’] ‘if’ Conditional
     *              |  ‘while’ SuiteExpr ':' Expr
     *              |  ‘try’ SuiteExpr
     *                  ( Except { Except} [‘finally’ SuiteExpr]
     *              |  'raise' SuiteExpr ['from' SuiteExpr]
     *              |  'return' [SuiteExpr]
     *              |  'for' Pattern1 'in' SuiteExpr ':' Expr
     *              |  'with' Expr2 ['as' id] ':' Expr
     *              |  MatchExpr
     *              |  ([SimpleExpr '.'] id | SimplePattern) (':= | assignop) Expr
     *              |  {Assignee}_',' (':= | assignop) Expr
     *              |  Expr2
     * Assignee   ::=  [SimpleExpr '.'] id
     *              |  SimpleExpr Arguments
     */
    def expr1(): Tree = in.token match
      case IF =>
        conditional(in.offset, If)
      case WHILE =>
        atSpan(in.skipToken()):
          val cond = inRegion(BeforeColon)(suiteExpr())
          val body = suite()
          WhileDo(cond, body)
      case TRY =>
        atSpan(in.skipToken()):
          val body = suiteExpr()
          if in.token == EXCEPT || in.token == FINALLY then
            val casesBuf = new ListBuffer[Except]
            while in.token == EXCEPT do
              casesBuf += except()
            val finalizer =
              if in.token == FINALLY then
                in.nextToken()
                suiteExpr()
              else EmptyTree
            TryExcept(body, casesBuf.toList, finalizer)
          else
            syntaxError(em"'except' or 'finally' expected but ${showToken(in.token)} found")
            body
      case RAISE =>
        atSpan(in.skipToken()):
          val exc = expr()
          if in.token == FROM then Throw(Thicket(List(exc, suiteExpr()))) // TODO: Maybe drop?
          else Throw(exc)
      case RETURN =>
        atSpan(in.skipToken()):
          val result =
            if in.token == COLON && !in.currentRegion.isClosedBy(COLON, RPAREN) then suite()
            else if isExprIntro then expr()
            else EmptyTree
          Return(result, EmptyTree)
      case FOR =>
        val gen = inRegion(BeforeColon)(forPrefix())
        val body = suite()
        ForDo(gen :: Nil, body)
      case WITH =>
        syntaxError(em"Not yet implemented: with statement")
        EmptyTree
      case MATCH =>
        matchExpr(in.offset, Match)
      case _ =>
        if isIdent(nme.inline)
           && !in.inModifierPosition()
           && canStartExprTokens.contains(in.lookahead.token)
        then
          val start = in.skipToken()
          in.token match
            case IF =>
              conditional(start, InlineIf)
            case MATCH =>
              matchExpr(start, InlineMatch)
            case _ =>
              syntaxError(em"no `inline` expected here")
              expr1()
        else
          val t = expr2()
          if in.token == ASSIGN then
            t match
              case Ident(_) | Select(_, _) | Apply(_, _) | PrefixOp(_, _) | PostfixOp(_, _) =>
                atSpan(startOffset(t), in.skipToken()):
                  Assign(t, expr())
              case _ =>
                t
          else t
    end expr1

    /**    Conditional ::= SuiteExpr ‘:’ Expr ['else' SuiteExpr | 'elif' Conditional]
     *     Check ::= SuiteExpr ['else' SuiteExpr]
     *
     * (start token is the token before)
     */
    def conditional(start: Offset, mkIf: (Tree, Tree, Tree) => If, canOmitThen: Boolean = false): If =
      atSpan(start, in.offset):
        val cond = inRegion(BeforeColon)(suiteExpr())
        val thenp =
          if in.token == COLON || !canOmitThen then
            suite()
          else EmptyTree
        val elsep =
          if in.token == ELSE then
            in.nextToken()
            suiteExpr()
          else if in.token == ELIF && !thenp.isEmpty then
            conditional(in.offset, mkIf)
          else EmptyTree
        mkIf(cond, thenp, elsep)

    /** Except            ::=  'except' [InfixType ['as' id]] Suite */
    def except(): Except =
      atSpan(accept(EXCEPT)):
        if in.token == COLON then
          Except(EmptyTree, atSpan(in.offset)(Ident(tpnme.WILDCARD)), suite())
        else
          val tpt = inRegion(BeforeColon)(infixType())
          val id = asNameOpt(isType = true)
          val rhs = suite()
          Except(tpt, id, rhs)

    /** AsNameOpt ::= ['as' id] */
    def asNameOpt(isType: Boolean): Ident =
      if in.token == AS then
        in.nextToken()
        if isType then typeIdent() else termIdent()
      else
        atSpan(in.offset)(Ident(tpnme.WILDCARD))

    /**  MatchExpr ::=  ['inline'] 'match' SuiteExpr ':' CaseClauses
     */
    def matchExpr(start: Offset, mkMatch: (Tree, List[CaseDef]) => Match): Match =
      atSpan(start, in.skipToken()):
        val selector = inRegion(BeforeColon)(suiteExpr())
        accept(COLON)
        mkMatch(selector, caseClauses(caseClause))

    /** CaseClauses         ::= CaseClause {CaseClause}
     *  TypeCaseClauses     ::= TypeCaseClause {TypeCaseClause}
     */
    def caseClauses(clause: () => CaseDef): List[CaseDef] =
      maybeIndented:
        val buf = new ListBuffer[CaseDef]
        buf += clause()
        while in.token == CASE do buf += clause()
        buf.toList

    /** CaseClause     ::= ‘case’ Pattern [Guard] (‘:’ Expr | 'if' MatchExpr)
     *  Guard          ::=  'if' OrExpr
     */
    def caseClause(): CaseDef =
      atSpan(accept(CASE)):
        val pat =
          inRegion(BeforeColon):
            withMode(currentMode | Mode.Pattern):
              pattern()
        if in.token == IF then
          in.nextToken()
          if in.token == MATCH
          then CaseDef(pat, EmptyTree, matchExpr(in.offset, SubMatch))
          else CaseDef(pat, orExpr(), suite())
        else CaseDef(pat, EmptyTree, suite())

    /** Expr2    ::=  OrExpr ['if' OrExpr 'else' Expr2]
     */
    def expr2(): Tree =
      expr2Rest(orExpr())

    def expr2Rest(t: Tree): Tree =
      if in.token == IF then
        atSpan(startOffset(t)):
          in.nextToken()
          val cond = orExpr()
          accept(ELSE)
          val elsep = expr2()
          PostfixIf(t, cond, elsep)
      else t

    /** OrExpr  :=  AndExpr {'or' (AndExpr | SuiteIfOuter)}
     */
    def orExpr(): Tree =
      orExprRest(andExpr())

    def orExprRest(t: Tree): Tree =
      if in.token == OR then
        atSpan(startOffset(t)):
          val op = identFromKW()
          val t2 =
            if in.token == COLON && !in.currentRegion.isClosedBy(RPAREN, COLON)
            then suite()
            else andExpr()
          InfixOp(t, op, orExprRest(t2))
      else t

    /** AndExpr  ::=  NotExpr {'and' (NotExpr | SuiteIfOuter)}
     */
    def andExpr(): Tree =
      andExprRest(notExpr())

    def andExprRest(t: Tree): Tree =
      if in.token == AND then
        atSpan(startOffset(t)):
          val op = identFromKW()
          val t2 =
            if in.token == COLON && !in.currentRegion.isClosedBy(RPAREN, COLON)
            then suite()
            else notExpr()
          InfixOp(t, op, andExprRest(t2))
      else t

    /** NotExpr    ::=  'not' (Comparison | SuiteIfOuter)
     */
    def notExpr(): Tree =
      if in.token == NOT then
        atSpan(in.offset):
          val op = termIdent()
          val t =
            if in.token == COLON && !in.currentRegion.isClosedBy(RPAREN, COLON)
            then suite()
            else comparison()
          PrefixOp(op, t)
      else comparison()

    /** Comparison  ::=  InfixExpr {CompOp (InfixExpr | SuiteIfOuter)}
     *  CompOp      ::=  '<' | '>' | '==' | '>=' | '<=' | '!=' | 'in' | 'not' 'in' | 'is' | 'is' 'not'
     */
    def comparison(): Tree =
      comparisonRest(infixExpr())

    def comparisonRest(t: Tree): Tree =
      def compareWith(op: Ident) =
        val t2 =
          if in.token == COLON && !in.currentRegion.isClosedBy(RPAREN, COLON)
          then suite()
          else infixExpr()
        InfixOp(t, op, t2)
      in.token match
        case IN =>
          compareWith(identFromKW())
        case IS =>
          if in.lookahead.token == NOT then
            val op = atSpan(in.skipToken()):
              in.nextToken()
              Ident(nme.is_not)
            compareWith(op)
          else
            compareWith(identFromKW())
        case NOT =>
          if in.lookahead.token == IN then
            val op = atSpan(in.skipToken()):
              in.nextToken()
              Ident(nme.not_in)
            compareWith(op)
          else
            t
        case IDENTIFIER if symbolicCompareOperaterNames.contains(in.name.nn) =>
          assert(false) // this is already handled by infixOps
        case _ =>
          t

    /**  InfixExpr ::= PrefixExpr {op (PrefixExpr | SuiteIfOuter)}
     */
    def infixExpr(): Tree =
      infixExprRest(prefixExpr())

    def infixExprRest(t: Tree): Tree =
      val infixOperand: () => Tree = () =>
        if in.token == COLON && !in.currentRegion.isClosedBy(COLON, RPAREN)
        then suite()
        else prefixExpr()
      infixOps(t, infixOperand)

    /** PrefixExpr       ::= [PrefixOperator'] SimpleExpr
     *  PrefixOperator   ::=  ‘-’ | ‘+’ | ‘~’
     */
    val prefixExpr: () => Tree = () =>
      if in.token == IDENTIFIER
        && nme.raw.isUnary(in.name)
        && canStartExprTokens.contains(in.lookahead.token)
        && in.lookahead.lineOffset < 0
      then
        if isNegatedNumber then
          simpleExprRest(literal())
        else
          atSpan(in.offset):
            PrefixOp(termIdent(), simpleExpr())
      else simpleExpr()

    /** SimpleExpr    ::= SimpleRef
     *                 |  Self
     *                 |  Literal
     *                 |  interpolatedString(Expr)
     *                 |  '_'
     *                 |  ‘new’ [ConstrApp] TemplateBody
     *                 |  '(' [ExprsInParens] ')'
     *                 |  '[' [ListExpr] ']'
     *                 |  '{' [ListExpr] '}'
     *                 |  '{' [DictExpr] '}'
     *                 |  SimpleExpr '.' id
     *                 |  SimpleExpr '?'
     *                 |  SimpleExpr TypeArgs
     *                 |  SimpleExr Arguments
     *                 |  INDENT Block DEDENT
     */
    def simpleExpr(): Tree =
      val t = in.token match
        case IDENTIFIER | BACKQUOTED_IDENT | SELF | SUPER =>
          if isNegatedNumber then literal()
          else simpleRefOrSelf()
        case INTERPOLATIONID =>
          interpolatedString(inPattern = false)
        case USCORE =>
          val start = in.skipToken()
          val pname = WildcardParamName.fresh()
          val param = ValDef(pname, TypeTree(), EmptyTree).withFlags(SyntheticTermParam)
            .withSpan(Span(start))
          placeholderParams = param :: placeholderParams
          atSpan(start):
            Ident(pname)
        case NEW =>
          newExpr()
        case LPAREN =>
          atSpan(in.offset):
            makeTupleOrParens(inParensWithCommas(exprsInParensOrBindings()))
        case LBRACKET =>
          atSpan(in.offset):
            SeqLiteral(listExprOrTypeParams(LBRACKET), EmptyTree)
        case LBRACE =>
          atSpan(in.offset):
            SetOrDictLiteral(listExprOrTypeParams(LBRACE))
        case INDENT =>
          indented(block())
        case _ =>
          if isLiteral then
            literal()
          else
            val start = in.lastOffset
            syntaxErrorOrIncomplete(IllegalStartSimpleExpr(tokenString(in.token).nn), expectedOffset)
            errorTermTree(start)
      simpleExprRest(t)

    def simpleExprRest(t: Tree): Tree =
      in.token match
        case DOT =>
          t match
            case Number(n, _) if n(0) == '-' =>
              val start = startOffset(t)
              val span = Span(start, in.lastOffset)
              warning(IllegalLiteral(ambiguous = true), start)
              unpatch(ctx.compilationUnit.source, span)
              patch(span, s"($n)")
            case _ =>
          in.nextToken()
          simpleExprRest(selector(t))
        case LBRACKET =>
          simpleExprRest(withMode(Mode.None)(typeArgsOpt(t)))
        case LPAREN =>
          val app = atSpan(startOffset(t), in.offset) { arguments(t) }
          simpleExprRest(app)
        case COLON if !in.currentRegion.isClosedBy(COLON, RPAREN) =>
          val app = atSpan(startOffset(t), in.offset) { Apply(t, suite() :: Nil) }
          simpleExprRest(app)
        case _ =>
          if in.isIdent(nme.?) then
            atSpan(startOffset(t)):
              simpleExprRest(PostfixOp(t, termIdent()))
          else t
    end simpleExprRest

    /**   ExprsInParens       ::=  ExprInParens {`,' ExprInParens}
     *    NamedExprsInParens  ::=  NamedExprInParens {‘,’ NamedExprInParens}
     *    NamedExprInParens   ::=  id '=' ExprInParens
     *    Bindings            ::=  Binding {`,' Binding}
     */
    def exprsInParensOrBindings(): List[Tree] =
      if in.token == RPAREN then Nil
      else in.currentRegion.withCommasExpected:
        val parseExpr =
          if in.isIdent && in.lookahead.token == EQUALS
          then named(exprInParens)
          else exprInParens
        def exprOrBinding() =
          if in.isIdent(nme.erased) then
            val param = binding()
            placeholderParams = param :: placeholderParams
              // Add the binding to placeholderParams, so that we can check for
              // leakage in checkNoEscapingPlaceholders
            param
          else parseExpr()
        commaSeparated(exprOrBinding)

    /** ExprInParens ::= Expr [‘:’ (Type  |  Annotation)]
     */
    val exprInParens: () => Tree = (maybeNamed: () =>
      val t = expr()
      if in.token == COLON then
        atSpan(startOffset(t)):
          in.nextToken()
          if in.token == AT then
            annotations().foldLeft(t)(Annotated)
          else
            val tpt = typ()
            if isLastPlaceholder(t) then
              val vd :: rest = placeholderParams.runtimeChecked
              placeholderParams =
                cpy.ValDef(vd)(tpt = tpt).withSpan(vd.span.union(tpt.span)) :: rest
            Typed(t, tpt)
      else t): @unchecked // silence initialization error

    /** Arguments ::= `(' [ExprsInParens] | ‘using’ ExprsInParens) `)'
     */
    def arguments(fn: Tree): Tree =
      atSpan(startOffset(fn)):
        inParensWithCommas:
          if in.token == RPAREN then
            Apply(fn, Nil)
          else if in.token == USING then
            in.nextToken()
            Apply(fn, commaSeparated(argument))
              .setApplyKind(ApplyKind.Using)
          else
            Apply(fn, commaSeparated(argument))

    /** Argumentss ::= {Arguments}
     */
    def argumentss(fn: Tree): Tree =
      if in.token == LPAREN then argumentss(arguments(fn)) else fn

    /** Argument  :=  [id '=']('*' SimpleExpr | ExprInParens Generator)
     */
    val argument: () => Tree =
      (maybeNamed: () =>
        if followingIsPrefixSpread() then
          atSpan(in.offset):
            PrefixOp(termIdent(), simpleExpr())
        else
          val t = exprInParens()
          if in.token == FOR then generator(t) else t): @unchecked // silence initialization error

    /** SimpleExpr    ::=  ‘new’ [ConstrApp] [TemplateBody]
     */
    def newExpr(): Tree =
      val start = in.skipToken()
      val parents =
        if canStartTypeTokens.contains(in.token) && in.token != LBRACE
        then constrApp() :: Nil
        else Nil
      val template = templateBodyOpt(emptyConstructor, parents, Nil, stat(local = false))
      New(template.withSpan(template.span.withStart(start)))

    /** ConstrApp  ::=  SimpleType {Arguments} {Annotations}
     *  Note: Annotations come after arguments so that the folloing is unambiguois
     *
     *    new Foo @ann (1, 2, 3)
     */
    val constrApp: () => Tree = () =>
      var t = simpleType()
      if in.token == LPAREN then t = argumentss(wrapNew(t, startOffset(t)))
      annotations().foldLeft(t)(Annotated)

    /** ListExpr  ::=  Expr ({',' Expr}  |  Generator)
     *  DictExpr  ::=  DictItem ({',' DictItem}  |  Generator)
     *  DictItem  ::=  Expr1 ':' Expr1
     *
     *  Parse a list, set, or dict expression. If start token `lead` is `[`
     *  also accept type parameters, but then require that the expression
     *  is followed by a `=>`.
     */
    def listExprOrTypeParams(lead: Token): List[Tree] =
      var typeParamSeen = false
      val elems = enclosedWithCommas(lead):
        if in.token == lead + 1 then Nil
        else
          var parseElem: () => Tree = () =>
            if lead == LBRACKET && followingIsTypeParam() then
              typeParamSeen = true
              typeParam(ParamOwner.PolyFun)
            else
              inRegion(BeforeColon)(expr())
          var elem = parseElem()
          if in.token == COLON && !typeParamSeen then
            in.nextToken()
            elem = Thicket(elem, expr())
            parseElem = () =>
              val key = inRegion(BeforeColon)(expr())
              accept(COLON)
              Thicket(key, expr())
          if in.token == COMMA then elem :: commaSeparatedRest(elem, parseElem)
          else if in.token == FOR && !typeParamSeen then generator(elem) :: Nil
          else elem :: Nil
      if typeParamSeen && in.token != ARROW then
        syntaxError(ExpectedTokenButFound(ARROW, in.token))
      elems

    /** Generator         ::=  ForPrefix [GeneratorRest]
     *  GeneratorRest     ::=  Generator
     *                      |  'if' SuiteExpr [GeneratorRest]
     */
    def generator(t: Tree): PostfixFor =
      atSpan(in.offset):
        val enumBuf = ListBuffer[Tree]()
        while in.token == FOR do
          enumBuf += forPrefix()
          while in.token == IF do
            in.nextToken()
            enumBuf += suiteExpr()
        assert(enumBuf.nonEmpty)
        PostfixFor(t, enumBuf.toList)

    /** ForPrefix  ::=  for' ['case'] SinglePattern 'in' orExpr */
    def forPrefix(): GenFrom =
      atSpan(accept(FOR)):
        val checkMode =
          if in.token == CASE then
            in.nextToken()
            GenCheckMode.FilterAlways
          else
            GenCheckMode.Check
        val pat = singlePattern()
        accept(IN)
        val expr = orExpr()
        GenFrom(pat, expr, checkMode)


    /* -------- PATTERNS ------------------------------------------- */

    /**  Pattern     ::=  SinglePattern { ‘|’ SinglePattern }
     */
    def pattern(): Tree =
      val pat = singlePattern()
      if isIdent(nme.raw.BAR) then
        atSpan(startOffset(pat)):
          Alternative(pat :: patternAlts())
      else pat

    def patternAlts(): List[Tree] =
      if isIdent(nme.raw.BAR) then
        in.nextToken()
        singlePattern() :: patternAlts()
      else Nil

    /** SinglePattern     ::=  InfixPattern ['as' id]
     */
    def singlePattern(): Tree =
      val pat = infixPattern()
      if in.token == AS then
        atSpan(startOffset(pat)):
          in.nextToken()
          As(pat, termIdent())
      else pat

    /**  InfixPattern ::= SimplePattern {id [nl] SimplePattern}
     */
    def infixPattern(): Tree =
      infixOps(simplePattern(), simplePattern)

    /** SimplePattern    ::=  PatVar
     *                    |   Literal
     *                    |   interpolatedString(Pattern)
     *                    |   `(' [Patterns | NamedPatterns] `)'
     *                    |   '[' SplicePatterns ']'
     *                    |   PathRef [TypeArgs] [PatternArguments]
     *  Patterns         ::=  {PatternInParens}_','
     *  PatternArguments ::=  '(' [SplicePatterns | NamedPatterns] ')'
     *  NamedPatterns    ::=  {NamedPattern}_','
     *  NamedPattern     ::=  id '=' PatternInParens
     */
    val simplePattern: () => Tree = () =>
      in.token match
      case IDENTIFIER | BACKQUOTED_IDENT | SELF | SUPER =>
        if isNegatedNumber then literal()
        else constrPatternRest(pathRef())
      case USCORE =>
        wildcardIdent()
      case INTERPOLATIONID =>
        interpolatedString(inPattern = true)
      case LPAREN =>
        atSpan(in.offset):
          inParensWithCommas:
            makeTupleOrParens:
              if in.token == RPAREN then Nil
              else
                val parseElem =
                  if in.isIdent && in.lookahead.token == EQUALS
                  then named(patternInParens)
                  else patternInParens
                commaSeparated(parseElem)
      case LBRACKET =>
        atSpan(in.offset):
          inBracketsWithCommas:
            SeqLiteral(splicePatterns(), EmptyTree)
      case _ =>
        if isLiteral then literal()
        else
          val start = in.lastOffset
          syntaxErrorOrIncomplete(IllegalStartOfSimplePattern(), expectedOffset)
          atSpan(Span(start, in.offset)) { Ident(nme.WILDCARD) }

    def constrPatternRest(t: Tree): Tree =
      if in.token == DOT then
        in.nextToken()
        constrPatternRest(selector(t))
      else
        withMode(currentMode | Mode.Pattern):
          patternArgumentsOpt(typeArgsOpt(t))

    /** PatVar           ::=  varid  |  `_'
     */
    def patVar(): Tree =
      if in.token == USCORE then
        wildcardIdent()
      else if in.token == IDENTIFIER && in.name.nn.isVarPattern then
        termIdent()
      else
        syntaxError(em"`*` must be followed by a pattern variable")
        atSpan(in.offset)(Ident(nme.WILDCARD))

    /** PatternInParens  ::=  Pattern
     *                    |   ['witness'] PatVar ‘:’ Type
     *                    |   [‘-’] numericLiteral ‘:’ Type
     */
    val patternInParens: () => Tree = () =>
      if in.isIdent(nme.witness)
          && (in.lookahead.isIdent || in.lookahead.token == USCORE)
      then
        atSpan(in.offset):
          val givenMod = atSpan(in.skipToken())(Mod.Given())
          val name = boundName()
          accept(COLON)
          val typed = atSpan(in.offset):
            Typed(Ident(nme.WILDCARD), typ())
          Bind(name, typed).withMods(addMod(EmptyModifiers, givenMod))
      else if isNegatedNumber || numericLitTokens.contains(in.token) then
        val pat = literal()
        if in.token == COLON then
          in.nextToken()
          Typed(pat, typ())
        else pat
      else
        pattern()

    /**  PatternArguments ::=  '(' [SplicePatterns | NamedPatterns] ')'
     */
    def patternArgumentsOpt(t: Tree): Tree =
      if in.token == LPAREN then
        atSpan(in.offset):
          inParensWithCommas:
            val args =
              if in.token == RPAREN then Nil
              else if in.isIdent && in.lookahead.token == EQUALS then
                commaSeparated(named(patternInParens))
              else splicePatterns()
            Apply(t, args)
      else t

    /** SplicePatterns   ::=  Patterns
     *                     |   [Patterns ‘,’] ‘*’ PatVar [‘,’ Patterns]
     *
     *   In the actual implementation, we allow multi-splices and filter them out on
     *   desugaring. So the actual parsing is for:
     *
     *  SplicePatterns   ::=  {PatternInParens | ‘*’ PatVar}_','
     */
    def splicePatterns(): List[Tree] =
      val parseArg = () =>
        if followingIsPrefixSpread() then
          atSpan(in.offset):
            PrefixOp(termIdent(), patVar())
        else patternInParens()
      commaSeparated(parseArg)

/* -------- MODIFIERS and ANNOTATIONS ------------------------------------------- */

    private def modOfToken(tok: Int, name: Name): Mod | Null = tok match
      case PRIVATE     => Mod.Private()
      case IDENTIFIER =>
        name match
          case nme.LAZYkw => Mod.Lazy()
          case nme.opaque => Mod.Opaque()
          case nme.OVERRIDEkw => Mod.Override()
          case nme.CASEkw => Mod.MagicCase()
          case nme.SEALEDkw => Mod.Sealed()
          case nme.open => Mod.Open()
          case nme.witness => Mod.Given()
          case nme.inline => Mod.Inline()
          case nme.transparent => Mod.Transparent()
          case nme.into => Mod.Into()
          case nme.erased => Mod.Erased()
          case nme.update => Mod.Update()
          case nme.consume => Mod.MagicConsume()
      case _ =>
        null

    /** Drop `private' modifier when followed by a qualifier. */
    private def normalize(mods: Modifiers): Modifiers =
      if mods.is(Private) && mods.hasPrivateWithin then mods &~ Private
      else mods

    private def modifier(): Mod | Null =
      val tok = in.token
      val name = in.name
      val mod = modOfToken(tok, name.nn)
      if mod != null then atSpan(in.skipToken())(mod) else null

    private def addModifier(mods: Modifiers): Modifiers =
      addModifier(mods, modifier().nn)

    private def addModifier(mods: Modifiers, mod: Mod): Modifiers =
      if mods.isOneOf(mod.flags) then
        syntaxError(RepeatedModifier(mod.flags.flagsString, source, mod.span), mod.span)
      addMod(mods, mod)

    def addFlag(mods: Modifiers, flag: FlagSet): Modifiers =
      mods.withAddedFlags(flag, Span(in.offset))

    def addMod(mods: Modifiers, mod: Mod): Modifiers =
      addFlag(mods, mod.flags).withAddedMod(mod)

    /** ClassQualifier ::= ‘[’ id ‘]’
     */
    def classQualifierOpt(mods: Modifiers): Modifiers =
      if in.token == LBRACKET then
        inBrackets:
          mods.withPrivateWithin(ident().toTypeName)
      else mods

    /** Modifier         ::=  AccessModifier
     *                     |  LocalModifier
     *                     |  'lazy'
     *                     |  ‘opaque’
     *                     |  ‘override’
     * AccessModifier    ::=  'private' [ClassQualifier]
     * LocalModifier     ::=  ‘sealed’
     *                     |  ‘open’
     *                     |  ‘witness’
     *                     |  ‘inline’
     *                     |  ‘transparent’
     *                     |  'into'
     *                     |  ‘erased’
     *                     |  ‘update’
     *                     |  ‘consume’
     * DefParamModifier  ::=  'inline'
     *                     |  'erased'
     *                     |  'consume'
     * ClsParamModifier  ::=  'private' [ClassQualifier]
     *                     |  DefParamModifier
     */
    def modifiers(allowed: FlagSet = ModifierFlags, start: Modifiers = EmptyModifiers): Modifiers =
      @tailrec
      def loop(mods: Modifiers): Modifiers =
        val token = in.token
        val mod = modOfToken(token, in.name.nn)
        if mod != null && mod.flags.isOneOf(allowed) then
          val mods1 = addMod(mods, atSpan(in.skipToken())(mod))
          loop(if token == PRIVATE then classQualifierOpt(mods1) else mods1)
        else if mod != null then
          syntaxError(em"Modifier `${in.name.nn}` is not allowed at this position")
          in.nextToken()
          loop(mods)
        else if in.token == NEWLINE && (mods.hasFlags || mods.hasAnnotations) then
          // skip newlines between modifiers
          in.nextToken()
          loop(mods)
        else
          mods
      normalize(loop(start))

    def allowedMods(local: Boolean): FlagSet =
      if local then MagicLocalModifierFlags else MagicModifierFlags

    /** Wrap annotation or constructor in New(...).<init> */
    def wrapNew(tpt: Tree, start: Offset): Select =
      atSpan(start):
        Select(New(tpt), nme.CONSTRUCTOR)

    /** Annotation        ::=  `@' PathRef [TypeArgs] {ParArguments}
     */
    def annot(): Tree =
      val start = accept(AT)
      val constr = pathRef()
      val nu = wrapNew(withMode(Mode.None)(typeArgsOpt(constr)), start)
      ensureApplied(argumentss(nu))

    def annotations(skipNewLines: Boolean = false): List[Tree] =
      if skipNewLines then newLineOptWhenFollowedBy(AT)
      if in.token == AT then annot() :: annotations(skipNewLines)
      else Nil

    def annotsAsMods(skipNewLines: Boolean = false): Modifiers =
      EmptyModifiers.withAnnotations(annotations(skipNewLines))

    def defAnnotsMods(allowed: FlagSet): Modifiers =
      modifiers(allowed, start = annotsAsMods(skipNewLines = true))

 /* -------- PARAMETERS ------------------------------------------- */

    /** TypeParams        ::=  ‘[’ TypeParam {‘,’ TypeParam} ‘]’
     *  TypeParam         ::=  {Annotation} [‘+’ | ‘-’] id [`^`] TypeAndCtxBounds ['=' Type]
     */
    def typeParamsOpt(paramOwner: ParamOwner): List[TypeDef] =
      if in.token == LBRACKET then
        inBracketsWithCommas:
          commaSeparated(() => typeParam(paramOwner))
      else Nil

    def typeParam(paramOwner: ParamOwner): TypeDef =
      val start = in.offset
      var mods = annotsAsMods() | Param
      if paramOwner.isClass then
        mods |= PrivateLocal
      val variance =
        if isIdent(nme.raw.PLUS) then Covariant
        else if in.isIdent(nme.raw.MINUS) then Contravariant
        else EmptyFlags
      if !variance.isEmpty then
        if paramOwner.acceptsVariance then mods |= variance
        else syntaxError(em"no `+/-` variance annotation allowed here")
        in.nextToken()
      atSpan(start, nameStart):
        val name = ident().toTypeName
        val isCap = hatOpt()
        val bounds = typeAndCtxBounds(name) match
          case bounds: TypeBoundsTree => bounds
          case bounds: ContextBounds if paramOwner.acceptsCtxBounds => bounds
          case ContextBounds(bounds, cxBounds) =>
            for cbound <- cxBounds do
              report.error(IllegalContextBounds(), cbound.srcPos)
            bounds
        val res = TypeDef(name, bounds).withMods(mods)
        if isCap then
          res.pushAttachment(CaptureVar, ())
          // putting the attachment here as well makes post-processing in the typer easier
          bounds.pushAttachment(CaptureVar, ())
        res

    /** hatOpt  ::=  ['^'] */
    def hatOpt(): Boolean =
      if isIdent(nme.UPARROW) then
        in.nextToken()
        true
      else false

    /** DefParams         ::=  '(' {DefParam}_',' ')'  |  UsingParams
     *  ClsParams         ::=  '(' {ClsParam}_',' ')'
     *                      |  '(' 'using' (ContextTypes | {ClsParam}_',') ')'
     *  ConstrParams      ::=  DefParams  |  UsingParams
     *  UsingParams       ::=  '(' 'using' (ContextTypes | {DefParam}_',') ')'
     *
     *  DefParam          ::=  {Annotation} {DefParamModifier} Param
     *  ClsParam          ::=  {Annotation} {ClsParamModifier} ['val' | 'var'] Param
     *  Param             ::=  id ':' ParamTypeSpraed ['=' Expr]
     */
    def termParams(
      paramOwner: ParamOwner,
      numLeadParams: Int,                     // number of parameters preceding this clause
      firstClause: Boolean = false            // clause is the first in regular list of clauses
    ): List[ValDef] =
      inParensWithCommas:
        var paramMods = EmptyModifiers
        if in.token == USING then
          paramMods = addMod(paramMods, atSpan(in.skipToken())(Mod.Given()))
          if in.token == RPAREN then
            syntaxError(em"`using` must be followed by a parameter")
        else if paramOwner.takesOnlyUsingClauses then
          syntaxError(ExpectedTokenButFound(USING, in.token))

        def param(): ValDef =
          val start = in.offset
          var mods = paramMods.withAnnotations(annotations())
          if paramOwner.isClass then
            mods = modifiers(MagicClsParamModifierFlags, mods | ParamAccessor)
            if in.token == VAL then
              in.nextToken() // TODO: in a case class mark somehow that this is tracked
            else if in.token == VAR then
              mods = addMod(mods, atSpan(in.skipToken()) { Mod.Var() })
            else if !(firstClause && paramOwner != ParamOwner.CaseClass) then
              mods |= PrivateLocal
          else
            mods |= Param

          atNameSpan(start):
            val name = ident()
            accept(COLON)
            val tpt = paramTypeSpread()
            val default =
              if in.token == EQUALS then { in.nextToken(); expr() }
              else EmptyTree
            if paramMods.mods.nonEmpty then
              paramMods = paramMods.withMods(Nil) // keep only flags, so that parameter positions don't overlap
            ValDef(name, tpt, default).withMods(mods)
        end param

        if in.token == RPAREN then Nil
        else
          val paramsAreNamed = !paramMods.is(Given) || in.token.match
            case AT | PRIVATE => true
            case IDENTIFIER =>
              paramModifierNames.contains(in.name.nn) || in.lookahead.token == COLON
            case _ => false
          val params =
            if paramsAreNamed then commaSeparated(() => param())
            else contextTypes(paramOwner, numLeadParams, paramMods)
          checkVarArgsRules(params)
          params
    end termParams

    // TODO: check at desugar?
    def checkVarArgsRules(vparams: List[ValDef]): Unit = vparams match
      case Nil =>
      case vparam :: rest =>
        vparam.tpt match
          case PostfixOp(_, op) if op.name == tpnme.raw.STAR =>
            if vparam.mods.isOneOf(GivenOrImplicit) then
              syntaxError(VarArgsParamCannotBeGiven(vparam.mods.is(Given)), vparam.tpt.span)
            if rest.nonEmpty then
              syntaxError(VarArgsParamMustComeLast(), vparam.tpt.span)
          case _ =>
        checkVarArgsRules(rest)

    /** ClsTermParamClauses   ::=  {ClsTermParamClause} [[nl] ‘(’ [‘implicit’] ClsParams ‘)’]
     *  ConstrParamClauses    ::=  ConstrParamClause {ConstrParamClause}
     *
     *  @return  The parameter definitions
     */
    def termParamss(paramOwner: ParamOwner, numLeadParams: Int = 0): List[List[ValDef]] =
      def recur(numLeadParams: Int, firstClause: Boolean): List[List[ValDef]] =
        newLineOptWhenFollowedBy(LPAREN)
        if in.token == LPAREN then
          val params = termParams(paramOwner, numLeadParams, firstClause)
          params :: recur(numLeadParams + params.length, firstClause = false)
        else Nil
      recur(numLeadParams, firstClause = true)

    def typeOrTermParams(paramOwner: ParamOwner, numLeadParams: Int = 0): List[ParamClause] =
      def recur(numLeadParams: Int, firstClause: Boolean): List[ParamClause] =
        newLineOptWhenFollowedBy(LPAREN)
        if in.token == LPAREN then
          val params = termParams(paramOwner, numLeadParams, firstClause)
          params :: recur(numLeadParams + params.length, firstClause = false)
        else if in.token == LBRACKET then
          typeParamsOpt(paramOwner) :: recur(numLeadParams, firstClause)
        else Nil
      recur(numLeadParams, firstClause = true)

    /** ParamType        ::=  [Arrow] Type
     *  ParamTypeSpread  ::=  [Arrow] TypeSpread
     */
    def paramType(): Tree = paramTypeOf(typ)
    def paramTypeSpread(): Tree = paramTypeOf(typeSpread)
    def paramTypeOf(parseCore: () => Tree): Tree =
      if in.token == ARROW || in.token == THINARROW then
        val isImpure = in.token == ARROW
        atSpan(in.skipToken()):
          if isImpure then ImpureByNameTypeTree(parseCore())
          else ByNameTypeTree(capturesAndResult(parseCore))
      else
        parseCore()

    /** TypeSpread  ::=  ['*'] Type
     */
    def typeSpread(): Tree =
      if followingIsPrefixSpread() then
        atSpan(in.offset):
          PrefixOp(termIdent(), typ())
      else typ()

    /** ContextTypes   ::=  {ParamType}_','
     */
    def contextTypes(paramOwner: ParamOwner, numLeadParams: Int, impliedMods: Modifiers): List[ValDef] =
      typesToParams(commaSeparated(paramType), paramOwner, numLeadParams, impliedMods)

    /**  Binding           ::= [`erased`] BoundName [`:' ParamType]
     */
    def binding(): ValDef =
      atSpan(in.offset):
        val mods1 = modifiers(allowed = Erased)
        val name = boundName()
        val tpt =
          if in.token == COLON then
            in.nextToken()
            paramType()
          else
            TypeTree().withSpan(Span(in.lastOffset))
        makeParameter(name, tpt, mods1)

    /** TypeAndCtxBounds  ::=  TypeBounds [ContextBounds]
     */
    def typeAndCtxBounds(pname: TypeName): Tree =
      val t = typeBounds()
      val cbs = contextBoundsOpt(pname)
      if cbs.isEmpty then t
      else atSpan((t.span `union` cbs.head.span).start) { ContextBounds(t, cbs) }

    /** ContextBounds     ::=  ':' ContextBound
     *                      |  ':' `{` ContextBound {`,` ContextBound} `}`
     */
    def contextBoundsOpt(pname: TypeName): List[Tree] =
      if in.token == COLON then
        in.nextToken()
        if in.token == LBRACE then
          inBracesWithCommas:
            commaSeparated(() => contextBound(pname))
        else
          contextBound(pname) :: Nil
      else
        Nil

    /** ContextBound      ::=  Type [`as` id] */
    def contextBound(pname: TypeName): Tree =
      val start = in.offset
      val t = typ()
      atSpan(startOffset(t)):
        val ownName =
          if in.token == AS then
            in.nextToken()
            ident()
          else EmptyTermName
        ContextBoundTypeTree(t, pname, ownName)

/* -------- DEFS ------------------------------------------- */

    def finalizeDef(md: MemberDef, mods: Modifiers): md.ThisTree[Untyped] =
      retrieveDocString(md.withMods(mods)).asInstanceOf[md.ThisTree[Untyped]]

    def retrieveDocString(md: MemberDef): MemberDef =
      md match
      case md: ValOrDefDef =>
        md.rhs match
          case Block((lit @ Literal(Constant(str: String))) :: rest, expr) =>
            val rhs1 = cpy.Block(md.rhs)(rest, expr)
            val md1 = md match
              case md: ValDef => cpy.ValDef(md)(rhs = rhs1)
              case md: DefDef => cpy.DefDef(md)(rhs = rhs1)
            md1.setComment(Some(Comment(lit.span, str)))
          case _ => md
      case md @ TypeDef(name, impl: Template) =>
        impl.body match
          case (lit @ Literal(Constant(str: String))) :: rest =>
            cpy.TypeDef(md)(name,
              cpy.Template(impl)(body = rest)
            ).setComment(Some(Comment(lit.span, str)))
          case _ => md
      case md => md

    /** Dcl       ::=  val ValDcl
     *              |  var ValDcl
     *              |  def DefDcl
     *              |  type TypeDef
     *  Def       ::=  val ValDef
     *              |  var ValDef
     *              |  def DefDef
     *              |  type TypeDef
     *              |  ['case'] ‘class’ ClassDef
     *              |  ['case'] ‘object’ ObjectDef
     *              |  ‘trait’ ClassDef
     *              |  ‘enum’ EnumDef
     *  TmplDef   ::=  Def
     *              |  Dcl
     *              |  'val' SelfDef
     *  SelfDef   ::=  'self' ':' Type
     */
    def defOrDcl(start: Offset, mods: Modifiers): Tree =
      in.token match
      case VAL =>
        in.nextToken()
        valDefOrDcl(start, mods)
      case VAR =>
        val mod = atSpan(in.skipToken()) { Mod.Var() }
        valDefOrDcl(start, addMod(mods, mod))
      case DEF =>
        in.nextToken()
        defDefOrDcl(start, mods)
      case TYPE =>
        in.nextToken()
        typeDef(start, mods)
      case CLASS =>
        in.nextToken()
        classDef(start, mods)
      case TRAIT =>
        in.nextToken()
        classDef(start, mods | Trait)
      case OBJECT =>
        in.nextToken()
        objectDef(start, mods | Module)
      case ENUM =>
        in.nextToken()
        enumDef(start, mods | Enum)
      case _ =>
        syntaxErrorOrIncomplete(ExpectedStartOfTopLevelDefinition())
        EmptyTree

    /** ValDef        ::=  BoundName [‘:’ Type] ‘=’ Expr
     *                  |  SinglePattern ‘=’ Expr
     *  VarDef        ::=  BoundName [‘:’ Type] ‘:=’ Expr
     *                  |  SinglePattern ‘:=’ Expr
     *  ValDcl        ::=  plainid ':' Type
     *  SimpleValDef  ::=  BoundName '=' Expr
     */
    def valDefOrDcl(start: Offset, mods: Modifiers): Tree =
      def initializer() =
        if in.token == ASSIGN then
          if !mods.is(Mutable) then
            syntaxError(ExpectedTokenButFound(EQUALS, in.token))
          in.nextToken()
          expr()
        else if in.token == EQUALS then
          if mods.is(Mutable) then
            syntaxError(ExpectedTokenButFound(ASSIGN, in.token))
          in.nextToken()
          expr()
        else EmptyTree

      atSpan(start, nameStart):
        if (in.token == IDENTIFIER || in.token == USCORE)
            && BitSet(COLON, EQUALS, ASSIGN).contains(in.lookahead.token)
        then
          val name = boundName()
          if in.token == USCORE then
            val tpt = typedOpt()
            accept(EQUALS)
            finalizeDef(ValDef(name, tpt, expr()), mods)
          else
            finalizeDef(ValDef(name, typedOpt(), initializer()), mods)
        else
          PatDef(mods, singlePattern() :: Nil, EmptyTree, initializer())
    end valDefOrDcl

    /** DefDef  ::=  plainid {DefParamClause} [‘:’ Type] ‘=’ Expr
     *  DefDcl  ::=  plainid {DefParamClause} [‘:’ Type]
     */
    def defDefOrDcl(start: Offset, mods: Modifiers, numLeadParams: Int = 0): DefDef =
      atSpan(start, nameStart):
        val name = plainid()
        val paramss = typeOrTermParams(ParamOwner.Def, numLeadParams)
        val tpt = typedOpt()
        val rhs =
          if in.token == EQUALS then
            in.nextToken()
            expr()
          else EmptyTree
        val ddef = DefDef(name, paramss, tpt, rhs)
        finalizeDef(ddef, addFlag(mods, Method))

    /** TypeDef    ::=  plainid [TypeParams] TypeAndCtxBounds [‘=’ Type]
     *               |  plainid `^` TypeAndCtxBounds [‘=’ TypeOrCapSet]
     */
    def typeDef(start: Offset, mods: Modifiers): Tree =
      atSpan(start, nameStart):
        val name = plainid().toTypeName
        val isCapDef = hatOpt()
        val tparams = typeParamsOpt(ParamOwner.Type)

        def makeTypeDef(rhs: Tree): Tree =
          val rhs1 = lambdaAbstract(tparams, rhs)
          val tdef = TypeDef(name, rhs1)
          if isCapDef then
            tdef.pushAttachment(CaptureVar, ())
            // putting the attachment here as well makes post-processing in the typer easier
            rhs.pushAttachment(CaptureVar, ())
          finalizeDef(tdef, mods)

        in.token match
          case EQUALS =>
            in.nextToken()
            makeTypeDef(typ())
          case SUBTYPE | SUPERTYPE =>
            val bounds = typeAndCtxBounds(name)
            if in.token == EQUALS then
              val eqOffset = in.skipToken()
              var rhs = typ()
              bounds match
                case bounds: TypeBoundsTree =>
                  rhs match
                    case mtt: MatchTypeTree =>
                      bounds match
                        case TypeBoundsTree(EmptyTree, upper, _) =>
                          rhs = MatchTypeTree(upper, mtt.selector, mtt.cases)
                        case _ =>
                          syntaxError(em"cannot combine lower bound and match type alias", eqOffset)
                    case _ =>
                      if mods.is(Opaque) then
                        rhs = TypeBoundsTree(bounds.lo, bounds.hi, rhs)
                      else
                        syntaxError(em"cannot combine bound and alias", eqOffset)
                  makeTypeDef(rhs)
                case bounds: ContextBounds =>
                  syntaxError(em"cannot combine context bound and alias", eqOffset)
                  makeTypeDef(rhs)
            else
              makeTypeDef(bounds)
          case SEMI | NEWLINE |COMMA | RBRACE | DEDENT | EOF =>
            makeTypeDef(typeAndCtxBounds(name))
          case _ =>
            syntaxErrorOrIncomplete(ExpectedTypeBoundOrEquals(in.token))
            makeTypeDef(TypeBoundsTree(EmptyTree, EmptyTree))
    end typeDef

    /** ClassDef ::= plainid ClassConstr [Template]
     */
    def classDef(start: Offset, mods: Modifiers): TypeDef =
      atNameSpan(start):
        val name = plainid().toTypeName
        val constr = classConstr(if mods.is(Case) then ParamOwner.CaseClass else ParamOwner.Class)
        val templ = templateOpt(constr, stat(local = false))
        finalizeDef(TypeDef(name, templ), mods)

   /** ObjectDef       ::= id TemplateOpt
     */
    def objectDef(start: Offset, mods: Modifiers): ModuleDef =
      atNameSpan(start):
        val name = plainid()
        val templ = templateOpt(emptyConstructor, stat(local = false))
        finalizeDef(ModuleDef(name, templ), mods)

    /**  EnumDef ::=  plainid ClassConstr InheritClauses EnumBody
     */
    def enumDef(start: Offset, mods: Modifiers): TypeDef =
      atNameSpan(start):
        val clsName = plainid().toTypeName
        val constr = classConstr(ParamOwner.Class)
        val templ = template(constr, enumStat)
        finalizeDef(TypeDef(clsName, templ), mods)

    /** SelfDef  ::=  'self' ':' Type
     */
    def selfDef(start: Offset): ValDef =
      atSpan(start, nameStart):
        accept(SELF)
        accept(COLON)
        ValDef(nme.self, typ(), EmptyTree)

    /** ClassConstr ::= [TypeParams] {Annotation} [AccessModifier] {ClsParamClause}
     */
    def classConstr(paramOwner: ParamOwner): DefDef =
      atSpan(in.lastOffset):
        val tparams = typeParamsOpt(paramOwner)
        val cmods = modifiers(Private, annotsAsMods())
        val vparamss = termParamss(paramOwner)
        makeConstructor(tparams, vparamss).withMods(cmods)

/* -------- TEMPLATES ------------------------------------------- */

    /** Template          ::=  InheritClauses [TemplateBody]
     *  InheritClauses    ::=  [‘extends’ {ConstrApp}_',']
     *                         [‘derives’ {QualId}_‘,’]
     *                         [‘uses’ {UseRef}_‘,’]
     */
    def template(constr: DefDef, stat: ListBuffer[Tree] => Unit, isEnumCase: Boolean = false): Template =
      val parents =
        if in.token == EXTENDS then
          in.nextToken()
          commaSeparated(constrApp)
        else Nil
      val derived =
        if !isEnumCase && in.token == DERIVES then
          in.nextToken()
          commaSeparated(() => convertToTypeId(qualId()))
        else Nil
      val derivedAndUses =
        if isEnumCase && in.token == USES then
          in.nextToken()
          derived ++ commaSeparated(useRef)
        else derived
      templateBodyOpt(constr, parents, derivedAndUses, stat)

    /** TemplateOpt = [Template]
     */
    def templateOpt(constr: DefDef, stat: ListBuffer[Tree] => Unit): Template =
      if canStartTemplateTokens.contains(in.token)
      then template(constr, stat)
      else
        checkNextNotIndented()
        Template(constr, Nil, Nil, EmptyValDef, Nil)

    /** [`extends' {ConstrApp}_','}] */
    def caseTemplate(constr: DefDef): Template =
      val parents =
        if in.token == EXTENDS then
          in.nextToken()
          commaSeparated(constrApp)
        else Nil
      Template(constr, parents, Nil, EmptyValDef, Nil)

    /** UseRef            ::=  CaptureRef [‘initially’] */
    def useRef(): UseRef =
      val ref = capRef()
      if isIdent(nme.initially) then
        atSpan(in.skipToken()):
          UseRef(ref, initially = true)
      else
        UseRef(ref, initially = false)

/* -------- STATEMENTS ------------------------------------------- */

    /** CommonStat  ::=  Import
     *                |  Extension
     *                |  EndMarker
     *                |  SimpleValDef
     *                |  Expr
     * BlockStat    ::=  CommonStat
     *                |  {Annotation [nl]} {LocalModifier} Def
     *                |  'if' Check
     *                |  'break'
     *                |  'continue'
     * TemplateStat ::=  CommonStat
     *                |  {Annotation [nl]} {Modifier} (Def | Dcl)
     *                |  Export
     *                |  'val' SelfDef
     * SelfDef      ::=  'self' ':' Type
     */
    def stat(local: Boolean)(stats: ListBuffer[Tree]): Unit =
      in.token match
        case IMPORT | EXPORT | FROM =>
          val allowed = if local then BitSet(IMPORT) else BitSet(IMPORT, EXPORT)
          stats += importOrExportClause(allowed)
        case EXTENSION =>
          stats += extensionDef(local)
        case END =>
          endMarker(stats)
        case STRINGLIT if in.lookahead.isStatSep && stats.nonEmpty =>
          trailingDocstring(stats.last)
        case BREAK =>
          stats += atSpan(in.skipToken())(Break())
        case CONTINUE =>
          stats += atSpan(in.skipToken())(Continue())
        case IF if local =>
          stats += conditional(in.offset, If, canOmitThen = true)
        case VAL if !local && in.lookahead.token == SELF =>
          stats += selfDef(in.skipToken())
        case _ =>
          stats += {
            if isDefIntro then
              defOrDcl(in.offset, defAnnotsMods(allowedMods(local)))
            else if isBoundName && (in.lookahead.token == EQUALS) then
              val mods = if local then EmptyModifiers else EmptyModifiers | PrivateLocal
              valDefOrDcl(in.offset, mods)
            else expr()
          }

    type ImportConstr = (Tree, List[ImportSelector]) => ImportOrExport

    /** Import       ::=  ‘from’ ImportSource 'import' ImportSelectors
     *                 |  'import' ImportSource Renaming
     *  Export       ::=  ‘from’ ImportSource 'export' ImportSelectors
     *                 |  'export' ImportSource Renaming
     *  ImportSource ::=  PathRef  |  '.' QualId
 s    */
    def importOrExportClause(allowed: TokenSet): Tree =
      atSpan(in.offset):
        var leadToken = in.token
        def checkKind() =
          if !allowed.contains(leadToken) then
            syntaxError(em"no ${if leadToken == EXPORT then "export" else "import"} allowed here")
        def constr: ImportConstr = if leadToken == EXPORT then Export(_, _)
          else Import(_: Tree, _: List[ImportSelector])
        if leadToken != FROM then checkKind()
        in.nextToken()
        val source =
          // Relative sources `.p` are represented as selections from EmptyTree.
          if in.token == DOT then dotSelectors(EmptyTree)
          else pathRef()
        if leadToken == FROM then
          if in.token == IMPORT || in.token == EXPORT then
            leadToken = in.token
            checkKind()
          acceptOneOf(IMPORT, EXPORT)
          mkImport(source, importSelectors(), constr)
        else
          // import p.a as b  is represened as   Import(p, [Importelector(a, b)])
          //
          // Imports where the source is not a Select are not possible in Scala
          // but are allowed in Magic. We represent them as follows:
          //
          // import a as b     ==> Import(a, [Importelector(EmptyTree, b)])
          // import a          ==> Import(a, [])
          val renamed = renaming()
          source match
            case Select(qual, name) if qual.isInstanceOf[RefTree] =>
              val end = source.span.end
              val start = end - name.length
              val imported = Ident(name).withSpan(Span(start, end))
              mkImport(qual, ImportSelector(imported, renamed) :: Nil, constr)
            case _ =>
              if renamed.isEmpty then
                mkImport(source, Nil, constr)
              else
                val imported = atSpan(startOffset(renamed))(Ident(EmptyTermName))
                mkImport(source, ImportSelector(imported, renamed) :: Nil, constr)

    /** ImportSelectors   ::=  {id Renaming ','} (id Renaming | '*')
     */
    def importSelectors(): List[ImportSelector] =
      if in.isIdent(nme.raw.STAR) then
        atSpan(in.offset)(ImportSelector(wildcardIdent())) :: Nil
      else
        val sel = atSpan(in.offset)(ImportSelector(termIdent(), renaming()))
        if in.token == COMMA then
          in.nextToken()
          sel :: importSelectors()
        else
          sel :: Nil

    /** Renaming  ::=  ['as' BoundName]
     */
    def renaming(): Tree =
      if in.token == AS then
        in.nextToken()
        if in.token == USCORE then wildcardIdent() else termIdent()
      else EmptyTree

    /** Create an import node and handle source version imports */
    def mkImport(source: Tree, selectors: List[ImportSelector], constr: ImportConstr): ImportOrExport =
      val imp = constr(source, selectors)
      languageImport(source) match
        case Some(prefix) =>
          imp match
            case imp: Import =>
              in.languageImportContext = in.languageImportContext.importContext(imp, NoSymbol)
            case _ =>
              syntaxError(em"cannot export from `language` module", imp.span)
          handleLanguageImport(prefix, selectors)
        case None =>
      imp

    /** Check validity of language import and possibly update source version.
     */
    def handleLanguageImport(prefix: TermName, selectors: List[ImportSelector]): Unit =
      for case ImportSelector(id @ Ident(imported), EmptyTree, _) <- selectors do
        if Feature.handleGlobalLanguageImport(prefix, imported) && !in.currentRegion.isOutermost then
          syntaxError(em"this language import is only allowed at the toplevel", id.span)
        if allSourceVersionNames.contains(imported) && prefix.isEmpty then
          if !in.currentRegion.isOutermost then
            syntaxError(em"source version import is only allowed at the toplevel", id.span)
          else if illegalSourceVersionNames.contains(imported) then
            val candidate =
              val nonMigration = imported.toString.replace("-migration", "")
              validSourceVersionNames.find(_.show == nonMigration)
            val baseMsg = em"`$imported` is not a valid source version"
            val msg = candidate match
              case Some(member) => baseMsg.append(i", did you mean language.`$member`?")
              case _ => baseMsg
            syntaxError(msg, id.span)
          else
            val sourceVersion = SourceVersion.valueOf(imported.toString)
            ctx.compilationUnit.sourceVersion match
              case Some(existing) if existing != sourceVersion =>
                syntaxError(em"conflicting source version import: $existing and $sourceVersion", id.span)
              case _ =>
                ctx.compilationUnit.sourceVersion = Some(sourceVersion)

    /** Extension  ::=  ‘extension’ [TypeParamClause] {UsingParamClause}
     *                  ‘(’ DefParam ‘)’ {UsingParamClause} ExtMethods
     *  ExtMethods  ::=  ExtMethod | ':' INDENT ExtMethod {semi ExtMethod} DEDENT
     */
    def extensionDef(local: Boolean): ExtMethods =
      def badParam() =
        syntaxError(em"extension clause needs exactly one regular parameter")
      atSpan(in.skipToken()):
        val tparams = typeParamsOpt(ParamOwner.ExtensionPrefix)
        val leadParamss = ListBuffer[List[ValDef]]()
        def numLeadParams = leadParamss.map(_.length).sum
        var extParams = termParams(ParamOwner.ExtensionPrefix, numLeadParams)
        leadParamss += extParams
        while isUsingClause(extParams) do
          extParams = termParams(ParamOwner.ExtensionPrefix, numLeadParams)
          leadParamss += extParams
        if extParams.length != 1 then badParam()
        while in.token == LPAREN do
          val extParams = termParams(ParamOwner.ExtensionPrefix, numLeadParams)
          leadParamss += extParams
          if !isUsingClause(extParams) then badParam()
        val methods =
          if in.token == COLON then
            in.nextToken()
            indented:
              statSeq(_ += extMethod(local, numLeadParams), "extension method")
          else extMethod(local, numLeadParams) :: Nil
        ExtMethods(joinParams(tparams, leadParamss.toList), methods)

    /** ExtMethod  ::=  {Annotation [nl]} {Modifier} ‘def’ DefDef
     *               |  Export
     */
    def extMethod(local: Boolean, numLeadParams: Int): Tree =
      if in.token == FROM || in.token == EXPORT then
        importOrExportClause(allowed = BitSet(EXPORT))
      else
        val start = in.offset
        val mods = defAnnotsMods(allowedMods(local))
        accept(DEF)
        defDefOrDcl(start, mods, numLeadParams)

    /** EndMarker  ::=  'end' plainid */
    def endMarker(stats: ListBuffer[Tree]): Unit =
      val start = in.skipToken()
      stats.lastOption match
        case Some(last: MemberDef) =>
          if isIdent(last.name) then
            if last.hasEndMarker then syntaxError(em"duplicate end marker")
            else last.withEndMarker()
            last.span = last.span.withEnd(in.lastCharOffset)
            in.nextToken()
          else
            syntaxError(em"misaligned end marker - ${last.name} expected")
            accept(IDENTIFIER)
        case _ =>
          syntaxError(em"misaligned end marker - does not follow a definition", Span(start, in.lastCharOffset))
          accept(IDENTIFIER)

    /** TrailingDocstring  ::=  stringLiteral
     */
    def trailingDocstring(stat: Tree): Unit = stat match
      case stat: MemberDef if stat.rawComment.isEmpty =>
        literal() match
          case lit @ Literal(Constant(str: String)) =>
            stat.setComment(Some(Comment(lit.span, str)))
      case _ =>

    /** EnumStat      ::=  TemplateStat
     *                  |  {Annotation [nl]} {Modifier} EnumCase
     */
    def enumStat(stats: ListBuffer[Tree]): Unit =
      if isDefIntro then
        val start = in.offset
        val mods = defAnnotsMods(MagicModifierFlags)
        stats += {
          if in.token == CASE then enumCase(start, mods)
          else defOrDcl(start, mods)
        }
      else if in.token == CASE then enumCase(in.offset, EmptyModifiers)
      else stat(local = false)(stats)

    /** EnumCase = `case' (id ClassConstr [`extends' ConstrApps] | ids)
     */
    def enumCase(start: Offset, mods: Modifiers): DefTree =
      val mods1 = mods | EnumCase
      accept(CASE)
      atSpan(start, nameStart):
        if in.token == IDENTIFIER && in.lookahead.token == COMMA then
          val ids = commaSeparated(plainTermIdent)
          PatDef(mods1, ids, TypeTree(), EmptyTree)
        else
          val name = plainid()
          val caseDef =
            if BitSet(LBRACKET, LPAREN, AT, PRIVATE).contains(in.token) then
              val constr = classConstr(ParamOwner.CaseClass)
              TypeDef(name.toTypeName, caseTemplate(constr))
            else
              ModuleDef(name, caseTemplate(emptyConstructor))
          finalizeDef(caseDef, mods1)

/* ---------- SCOPES ------------------------------- */

    def statSeq(stat: ListBuffer[Tree] => Unit, statKind: String = "statement"): List[Tree] =
      checkNoEscapingPlaceholders:
        val stats = new ListBuffer[Tree]
        while
          val prevSize = stats.size
          stat(stats)
          statSepOrEnd(stats, noPrevStat = stats.size == prevSize, statKind)
        do ()
        stats.toList

    /** [semi]
     *
     *  Parse statement separators, in grammar. Ensure that there is at least
     *  one statement separator unless the next token terminates a statement sequence.
     *  @param   stats      the statements parsed so far
     *  @param   noPrevStat true if there was no immediately preceding statement parsed
     *  @param   statKind   a string indicating what kind of statement is parsed
     *  @return  true if the statement sequence continues, false if it terminates.
     */
    def statSepOrEnd(stats: ListBuffer[Tree], noPrevStat: Boolean, statKind: String = "statement"): Boolean =
      in.observeDedented()
      val continues = isStatSep
      if continues then
        while
          in.nextToken()
          isStatSep
        do ()
      else if !isStatSeqEnd then
        val found = in.token
        val statFollows = mustStartStatTokens.contains(found)
        val extra =
          if in.isOperator && Tokens.canStartExprTokens.contains(in.lookahead.token) then
            " - did you intend to apply an infix operator? If so, wrap the right operand in parentheses or in a block."
          else ""
        syntaxError(
          if noPrevStat then IllegalStartOfStatement(statKind, isModifier, statFollows)
          else em"end of $statKind expected but ${showToken(found)} found$extra")
        if !statFollows then
          in.nextToken() // needed to ensure progress; otherwise we might cycle forever
          skip()
      continues

    /** Block ::=  {BlockStat}_{semi}  |  CaseClauses
     */
    def block(): Tree =
      atSpan(in.offset):
        if in.token == CASE then
          Match(EmptyTree, caseClauses(() => caseClause()))
        else
          val stats = statSeq(stat(local = true))
          def isExpr(stat: Tree) = stat match
            case _: (DefTree | ImportOrExport | ExtMethods | Break | Continue) => false
            case _ => true
          if stats.nonEmpty && isExpr(stats.last) then
            val inits = stats.init
            val last = stats.last
            if inits.isEmpty then last else Block(inits, last)
          else Block(stats, EmptyTree)

    /** TemplateBody ::=  ':' INDENT {TemplateStat}_semi DEDENT
     *  EnumBody     ::=  ':' INDENT {EnumStat}_semi DEDENT
     */
    def templateBodyOpt(constr: DefDef, parents: List[Tree], derived: List[Tree], stat: ListBuffer[Tree] => Unit): Template =
      val stats =
        if in.token == COLON then
          in.nextToken()
          indented(statSeq(stat))
        else Nil
      Template(constr, parents, derived, EmptyValDef, stats)

    /** CompilationUnit   ::=  {TemplateStat}_semi
     */
    def compilationUnit(): Tree =
      wrapInPackage(maybeWrapInModule(statSeq(stat(local = false))))

    /**spec
     *  Lookup the path of the `compilationUnit.source` file. Follow directories
     *  outwards as long as each directory has a `package.mc` file. The outermost
     *  such directory is the root of the project. Then generate a PackageDef definition
     *  corresponding to the path from the root to the directory where source is
     *  that wraps `ts`.
     */
    def wrapInPackage(ts: List[Tree]): Tree =
      def isPackageDir(dir: io.Directory) = (dir / "package.mc").isFile

      /** The names of the directories from below the project root down to `dir` */
      def pkgPath(dir: io.Directory, acc: List[String]): List[String] =
        val parent = dir.parent
        if dir.jpath.getParent != null && isPackageDir(parent)
        then pkgPath(parent, dir.name :: acc)
        else acc

      val pkgNames =
        val jpath = source.file match
          case null => null
          case file => file.jpath
        if jpath == null then Nil
        else
          val dir = io.Path(jpath).toAbsolute.parent
          if isPackageDir(dir) then pkgPath(dir, Nil) else Nil

      pkgNames match
        case Nil =>
          PackageDef(Ident(nme.EMPTY_PACKAGE), ts)
        case name :: names =>
          val pid = names.foldLeft[RefTree](Ident(name.toTermName)): (qual, name) =>
            Select(qual, name.toTermName)
          PackageDef(pid, ts)
    end wrapInPackage

    def maybeWrapInModule(stats: List[Tree]): List[Tree] =
      val sourceName = ctx.compilationUnit.source.name.takeWhile(_ != '.')
      def matchesFileName(stat: Tree) = stat match
        case stat @ (ModuleDef(_, _) | TypeDef(_, _: Template)) =>
          stat.name.toString == sourceName
        case _: Import =>
          true
        case _ =>
          false
      if stats.forall(matchesFileName) then
        stats
      else
        ModuleDef(
          sourceName.toTermName,
          Template(emptyConstructor, Nil, Nil, EmptyValDef, stats)
        ) :: Nil

    /** This is the general parse entry point.
     *  Overridden by ScriptParser
     *  CompilationUnit   ::=  TemplateStats
     */
    def parse(): Tree =
      val t = compilationUnit()
      accept(EOF)
      t
  }
}
