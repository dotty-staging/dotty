package dotty.tools
package dotc
package magic

import core.Names.*, core.Contexts.*, core.Decorators.*, util.Spans.*
import core.StdNames.*, core.Comments.*
import util.SourceFile
import util.Chars.*
import util.SourcePosition
import util.Spans.Span
import config.Config
import Tokens.*
import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.collection.immutable.SortedMap
import rewrites.Rewrites.patch
import config.Feature
import config.Feature.{migrateTo3, sourceVersion}
import config.SourceVersion.{`3.0`, `3.0-migration`}
import config.MigrationVersion
import reporting.*
import Character.{isUnicodeIdentifierPart, isUnicodeIdentifierStart, isValidCodePoint, toCodePoint}

import java.util.Objects
import dotty.tools.dotc.reporting.Message.rewriteNotice
import dotty.tools.dotc.config.Feature.sourceVersion

object Scanners {

  /** Offset into source character array */
  type Offset = Int

  /** An undefined offset */
  val NoOffset: Offset = -1

  type Token = Int

  private val identity: IndentWidth => IndentWidth = Predef.identity

  trait TokenData {

    /** the next token */
    var token: Token = EMPTY

    /** the offset of the first character of the current token */
    var offset: Offset = 0

    /** the offset of the character following the token preceding this one */
    var lastOffset: Offset = 0

    /** the offset of the newline immediately preceding the token, or -1 if
     *  token is not preceded by a newline.
     */
    var lineOffset: Offset = -1

    /** the name of an identifier */
    var name: SimpleName | Null = null

    /** the string value of a literal */
    var strVal: String | Null = null

    /** the base of a number */
    var base: Int = 0

    /** the delimiter character of a string, relevant only for STRINGLIT tokens */
    var delimChar: Char = 0

    /** the number of leading delimiters of a string, relevant only for STRINGLIT tokens */
    var delimCount = 0

    def copyFrom(td: TokenData): Unit = {
      this.token = td.token
      this.offset = td.offset
      this.lastOffset = td.lastOffset
      this.lineOffset = td.lineOffset
      this.name = td.name
      this.strVal = td.strVal
      this.base = td.base
      this.delimChar = td.delimChar
      this.delimCount = td.delimCount
    }

    def isStatSep = token == NEWLINE || token == SEMI
    def isIdent = token == IDENTIFIER || token == BACKQUOTED_IDENT
    def isIdent(name: Name) = token == IDENTIFIER && (this.name eq name)

    /** Is current token first one after a newline? */
    def isAfterLineEnd: Boolean = lineOffset >= 0

    def isOperator =
      token == BACKQUOTED_IDENT
      || token == IDENTIFIER && isOperatorPart(name.nn(name.nn.length - 1))

    def isArrow = arrowTokens.contains(token)
    def isFatArrow = token == ARROW || token == CTXARROW

    def isBoundName = token == IDENTIFIER || token == USCORE
  }

  class Scanner(source: SourceFile, override val startFrom: Offset = 0, limit: Offset = -1)(using Context) extends StringReader with TokenData:
    val buf: String = source.textContent()
    val endIdx = if limit >= 0 && limit < buf.length then limit else buf.length
    // Errors -----------------------------------------------------------------

    /** the last error offset
      */
    var errOffset: Offset = NoOffset

    /** Generate an error at the given offset */
    def error(msg: Message, off: Offset = offset): Unit = {
      errorButContinue(msg, off)
      token = ERROR
      errOffset = off
    }

    def errorButContinue(msg: Message, off: Offset = offset): Unit =
      report.error(msg, sourcePos(off))

    /** signal an error where the input ended in the middle of a token */
    def incompleteInputError(msg: Message): Unit = {
      report.incompleteInputError(msg, sourcePos())
      token = EOF
      errOffset = offset
    }

    def sourcePos(off: Offset = offset): SourcePosition =
      source.atSpan(Span(off))

    // Setting token data ----------------------------------------------------

    protected def initialCharBufferSize = 1024

    /** A character buffer for literals
      */
    protected val litBuf = java.lang.StringBuilder(initialCharBufferSize)

    /** append Unicode character to "litBuf" buffer
     */
    protected def putChar(c: Char): Unit = litBuf.append(c)

    /** Finish an IDENTIFIER with `this.name`. */
    inline def finishNamed(): Unit = finishNamedToken(IDENTIFIER)

    /** Clear buffer and set name and token.
     *  If `target` is different from `this`, don't treat identifiers as end tokens.
     */
    def finishNamedToken(idtoken: Token): Unit =
      val idname = termName(litBuf.toString)
      litBuf.setLength(0)
      name = idname
      token = if idtoken == IDENTIFIER then kwMap.getOrElse(idname, IDENTIFIER) else idtoken

    /** Clear buffer and set string */
    def setStrVal(): Unit =
      strVal = litBuf.toString
      litBuf.setLength(0)

    inline def isNumberSeparator(c: Char): Boolean = c == '_'

    def removeNumberSeparators(s: String): String = s.replace("_", "")

    // disallow trailing numeric separator char, but continue lexing
    def checkNoTrailingSeparator(): Unit =
      if !litBuf.isEmpty && isNumberSeparator(litBuf.charAt(litBuf.length - 1)) then
        errorButContinue(em"trailing separator is not allowed", offset + litBuf.length - 1)

    val keepComments = !ctx.settings.XdropComments.value

    /** A switch whether operators at the start of lines can be infix operators */
    private[Scanners] var allowLeadingInfixOperators = true

    var debugTokenStream = false
    val showLookAheadOnDebug = false

    //TODO Move to Parser?
    private var myLanguageImportContext: Context = ctx
    def languageImportContext = myLanguageImportContext
    final def languageImportContext_=(c: Context) = myLanguageImportContext = c

    /* A Buffer for comments */
    private val commentBuf = new mutable.ListBuffer[Comment]

    /** Return a list of all the comments */
    def comments: List[Comment] = commentBuf.toList

    /** A buffer for comments */
    private val currentCommentBuf = java.lang.StringBuilder(initialCharBufferSize)

    def newTokenData: TokenData = new TokenData {}

    /** We need one token lookahead and one token history
     */
    val next = newTokenData
    private val prev = newTokenData

    /** The current region. This is initially an Indented region with zero indentation width. */
    var currentRegion: Region = Indented(IndentWidth.Zero, EMPTY, null)

// Error recovery ------------------------------------------------------------

    private def lastKnownIndentWidth: IndentWidth =
      def recur(r: Region): IndentWidth =
        val knownWidth = r.knownWidth
        if knownWidth == null then recur(r.enclosing) else knownWidth
      recur(currentRegion)

    private var skipping = false

    /** Skip on error to next safe point.
     */
    def skip(): Unit =
      val lastRegion = currentRegion
      skipping = true
      def atStop =
        token == EOF
        || (currentRegion eq lastRegion)
            && (isStatSep
                || closingParens.contains(token) && lastRegion.existsEnclosing(_.closedBy == token)
                || token == COMMA && lastRegion.existsEnclosing(_.commasExpected)
                || token == DEDENT && indentWidth(offset) < lastKnownIndentWidth)
          // stop at DEDENT if the new indentwidth is smaller than the indent width of
          // currentRegion. This corrects for the problem that sometimes we don't see an INDENT
          // when skipping and therefore might erroneously end up syncing on a nested DEDENT.
      if debugTokenStream then
        println(s"\nSTART SKIP AT ${sourcePos().line + 1}, $this in $currentRegion")
      var noProgress = 0
        // Defensive measure to ensure we always get out of the following while loop
        // even if source file is weirdly formatted (i.e. we never reach EOF)
      var prevOffset = offset
      while !atStop && noProgress < 3 do
        nextToken()
        if offset <= prevOffset then
          noProgress += 1
        else
          prevOffset = offset
          noProgress = 0
      if debugTokenStream then
        println(s"\nSTOP SKIP AT ${sourcePos().line + 1}, $this in $currentRegion")
      if token == DEDENT then dropUntil(_.isInstanceOf[Indented])
      skipping = false

// Get next token ------------------------------------------------------------

    /** Are we directly in a multiline string interpolation expression?
     *  @pre inStringInterpolation
     */
    private def inMultiLineInterpolation = currentRegion match
      case InString(_, delimCount, _) => delimCount >= 3
      case _ => false

    /** read next token and return last offset
     */
    def skipToken(): Offset = {
      val off = offset
      nextToken()
      off
    }

    private inline def dropUntil(inline matches: Region => Boolean): Unit =
      while !matches(currentRegion) && !currentRegion.isOutermost do
        currentRegion = currentRegion.enclosing

    def adjustRegions(lastToken: Token): Unit = (lastToken: @switch) match {
      case LPAREN | LBRACKET =>
        currentRegion = InParens(lastToken, currentRegion)
      case LBRACE =>
        currentRegion = InBraces(currentRegion)
      case RBRACE =>
        dropUntil(_.isInstanceOf[InBraces])
        if !currentRegion.isOutermost then currentRegion = currentRegion.enclosing
      case RPAREN | RBRACKET =>
        currentRegion match {
          case InParens(prefix, outer) if prefix + 1 == lastToken => currentRegion = outer
          case _ =>
        }
      case DEDENT =>
        currentRegion match
          case r: Indented if !r.isOutermost => currentRegion = r.enclosing
          case _ =>
      case STRINGLIT =>
        currentRegion match {
          case InString(_, _, outer) => currentRegion = outer
          case _ =>
        }
      case _ =>
    }

    /** Read a token or copy it from `next` tokenData */
    private def getNextToken(lastToken: Token): Unit =
      if next.token == EMPTY then
        lastOffset = lastCharOffset
        currentRegion match
          case r: InString if lastToken != STRINGPART => fetchStringPart(r)
          case _ => fetchToken()
        if token == ERROR then adjustRegions(STRINGLIT) // make sure we exit enclosing string literal
      else
        this.copyFrom(next)
        next.token = EMPTY

    /** Produce next token, filling TokenData fields of Scanner.
     */
    def nextToken(): Unit =
      val lastToken = token
      val lastName = name
      adjustRegions(lastToken)
      getNextToken(lastToken)
      if isAfterLineEnd then handleNewLine(lastToken)
      postProcessToken()
      printState()

    private def postProcessToken(): Unit = token match
      case CASE =>
        if lookahead.token == CLASS || lookahead.token == OBJECT then token = IDENTIFIER
      case COMMA =>
        skipTrailingComma()
      case RBRACE | RPAREN | RBRACKET =>
        closeIndented()
      case EOF =>
        if !source.maybeIncomplete then closeIndented()
      case _ =>

    final def printState() =
      if debugTokenStream && (showLookAheadOnDebug || !isInstanceOf[LookaheadScanner]) then
        print(s"[$show${if isInstanceOf[LookaheadScanner] then "(LA)" else ""}]")

    /** Insert `token` at assumed `offset` in front of current one. */
    def insert(token: Token, offset: Int) = {
      assert(next.token == EMPTY, next)
      next.copyFrom(this)
      this.offset = offset
      this.token = token
    }

    /** A leading symbolic or backquoted identifier is treated as an infix operator if
      *   - it does not follow a blank line, and
      *   - it is followed by at least one whitespace character and a
      *     token that can start an expression.
      *   - if the operator appears on its own line, the next line must have at least
      *     the same indentation width as the operator. See pos/i12395 for a test where this matters.
      */
    def isLeadingInfixOperator(nextWidth: IndentWidth = indentWidth(offset), inConditional: Boolean = true) =
      allowLeadingInfixOperators
      && isOperator
      && (isWhitespace(ch) || ch == LF
          || (token == THINARROW || token == THINCTXARROW)
              && ch == '{'
          )
      && !pastBlankLine
      && {
        // Is current lexeme  assumed to start an expression?
        // This is the case if the lexime is one of the tokens that
        // starts an expression or it is a COLONeol. Furthermore, if
        // the previous token is in backticks, the lexeme may not be a binary operator.
        // I.e. in
        //
        //   a
        //   `x` += 1
        //
        // `+=` is not assumed to start an expression since it follows an identifier
        // in backticks and is a binary operator. Hence, `x` is not classified as a
        // leading infix operator.
        def assumeStartsExpr(lexeme: TokenData) =
          (canStartExprTokens.contains(lexeme.token) || lexeme.token == COLON)
          && (!lexeme.isOperator || nme.raw.isUnary(lexeme.name))
        val lookahead = LookaheadScanner()
        lookahead.allowLeadingInfixOperators = false
          // force a NEWLINE a after current token if it is on its own line
        lookahead.nextToken()
        assumeStartsExpr(lookahead)
        || lookahead.token == NEWLINE
           && assumeStartsExpr(lookahead.next)
           && indentWidth(offset) <= indentWidth(lookahead.next.offset)
      }
      && {
        currentRegion match
          case r: Indented =>
            r.width <= nextWidth
            || {
              r.outer match
                case null => true
                case ro @ Indented(outerWidth, _, _) =>
                  outerWidth < nextWidth && !ro.otherIndentWidths.contains(nextWidth)
                case outer =>
                  outer.indentWidth < nextWidth
            }
          case _ => true
      }

    /** The indentation width of the given offset. */
    def indentWidth(offset: Offset, buf: String = this.buf): IndentWidth =
      import IndentWidth.{Run, Conc}
      def recur(idx: Int, ch: Char, n: Int, k: IndentWidth => IndentWidth): IndentWidth =
        if (idx < 0) k(Run(ch, n))
        else {
          val nextChar = buf.charAt(idx)
          if (nextChar == LF) k(Run(ch, n))
          else if (nextChar == ' ' || nextChar == '\t')
            if (nextChar == ch)
              recur(idx - 1, ch, n + 1, k)
            else {
              val k1: IndentWidth => IndentWidth = if (n == 0) k else Conc(_, Run(ch, n))
              recur(idx - 1, nextChar, 1, k1)
            }
          else recur(idx - 1, ' ', 0, identity)
        }
      recur(offset - 1, ' ', 0, identity)
    end indentWidth

    /** Handle newlines, possibly inserting an INDENT, DEDENT, NEWLINE, or NEWLINES token
     *  in front of the current token. This depends on whether indentation is significant or not.
     *
     *  Indentation is _significant_ if we are not inside a
     *  {...}, [...], (...), case ... => pair, nor in a if/while condition
     *  (i.e. currentRegion is empty).
     *
     *  There are three rules:
     *
     *   1. Insert NEWLINE if
     *
     *      - the closest enclosing sepRegion is { ... } or for ... do/yield,
     *         or we are on the toplevel, i.e. currentRegion is empty, and
     *      - the previous token can end a statement, and
     *      - the current token can start a statement, and
     *      - the current token is not a leading infix operator, and
     *      - if indentation is significant then the current token starts at the current
     *        indentation width or to the right of it.
     *
     *   2. Insert INDENT if
     *
     *      - indentation is significant, and
     *      - the last token can start an indentation region.
     *      - the indentation of the current token is strictly greater than the previous
     *        indentation width, or the two widths are the same and the current token is
     *        one of `:` or `match`.
     *
     *      The following tokens can start an indentation region:
     *
     *         :  =  =>  <-  if  then  else  while  do  try  catch
     *         finally  for  yield  match  throw  return  with
     *
     *      Inserting an INDENT starts a new indentation region with the indentation of the current
     *      token as indentation width.
     *
     *   3. Insert DEDENT if
     *
     *      - indentation is significant, and
     *      - the indentation of the current token is strictly less than the
     *        previous indentation width,
     *      - the current token is not a leading infix operator.
     *
     *      Inserting an DEDENT closes an indentation region. In this case, issue an error if
     *      the indentation of the current token does not match the indentation of some previous
     *      line in an enclosing indentation region.
     *
     *      If a token is inserted and consumed, the original source token is still considered to
     *      start a new line, so the process that inserts an DEDENT might repeat several times.
     *
     *  Indentation widths are strings consisting of spaces and tabs, ordered by the prefix relation.
     *  I.e. `a <= b` iff `b.startsWith(a)`. If indentation is significant it is considered an error
     *  if the current indentation width and the indentation of the current token are incomparable.
     */
    def handleNewLine(lastToken: Token) =
      var newlineIsSeparating = false
      var lastWidth = IndentWidth.Zero
      var indentPrefix = EMPTY
      val nextWidth = indentWidth(offset)

      // If nextWidth is an indentation level not yet seen by enclosing indentation
      // region, invoke `handler`.
      inline def handleNewIndentWidth(r: Region, inline handler: Indented => Unit): Unit = r match
        case r @ Indented(curWidth, prefix, outer)
        if curWidth < nextWidth && !r.otherIndentWidths.contains(nextWidth) && nextWidth != lastWidth =>
          handler(r)
        case _ =>

      /** Does this line start with a parnethesis/bracket/brace that is seen
       *  as a continuation of the previous line? This is the case if the
       *  new line is indented relative to the previous one and there is
       *  no empty line in between.
       */
      inline def isContinuingParenthesis =
        openParensTokens.contains(token)
        && lastWidth < nextWidth
        && !pastBlankLine

      if lastToken == ARROW || lastToken == CTXARROW then
        // `=>` at end of line: drop single-line suite region, and open indented.
        while currentRegion.closedBy == ENDsuite do
          currentRegion = currentRegion.outer.nn

      currentRegion match
        case r: Indented =>
          lastWidth = r.width
          newlineIsSeparating = lastWidth <= nextWidth || r.isOutermost
          indentPrefix = r.prefix
        case _: InString | _: InSuite => ()
        case r =>
          r.proposeKnownWidth(nextWidth, lastToken)
          lastWidth = r.indentWidth
          newlineIsSeparating = r.isInstanceOf[InBraces]

      // can emit DEDENT if line is not non-empty blank line at EOF
      inline def isTrailingBlankLine: Boolean =
        token == EOF && {
          val end = endIdx - 1 // take terminal NL as empty last line
          val prev = buf.lastIndexWhere(!isWhitespace(_), end = end)
          prev < 0 || end - prev > 0 && isLineBreakChar(buf.charAt(prev))
        }

      inline def canDedent: Boolean =
           lastToken != INDENT
        && !isLeadingInfixOperator(nextWidth)
        && !statCtdTokens.contains(lastToken)
        && !isTrailingBlankLine

      if currentRegion.closedBy == ENDsuite then
        insert(ENDsuite, lineOffset)
      else if newlineIsSeparating
         && canEndStatTokens.contains(lastToken)
         && canStartStatTokens.contains(token)
         && !isLeadingInfixOperator(nextWidth)
         && !isContinuingParenthesis
      then
        insert(NEWLINE, lineOffset)
      else if nextWidth < lastWidth then
        if currentRegion.isOutermost then
          if nextWidth < lastWidth then currentRegion = topLevelRegion(nextWidth)
        else if canDedent then
          currentRegion match
            case r: Indented =>
              insert(DEDENT, offset)
              handleNewIndentWidth(r.enclosing, ir =>
                if next.token == DOT
                    && !nextWidth.isClose(r.indentWidth)
                    && !nextWidth.isClose(ir.indentWidth)
                then
                  ir.otherIndentWidths += nextWidth
                else
                  val lw = lastWidth
                  errorButContinue(
                    em"""The start of this line does not match any of the previous indentation widths.
                        |Indentation width of current line : $nextWidth
                        |This falls between previous widths: ${ir.width} and $lw"""))
            case r =>
              if skipping then
                if r.enclosing.isClosedByUndentAt(nextWidth) then
                  insert(DEDENT, offset)
              else if r.isInstanceOf[InBraces] && !closingRegionTokens.contains(token) then
                report.warning(IndentationWarning(isLeft = true, missing = RBRACE), sourcePos())
      else if lastWidth < nextWidth then
        if canStartIndentTokens.contains(lastToken) then
          currentRegion = Indented(nextWidth, lastToken, currentRegion)
          insert(INDENT, offset)
      else if lastWidth != nextWidth then
        val lw = lastWidth
        errorButContinue(spaceTabMismatchMsg(lw, nextWidth))
      if token != DEDENT then
        handleNewIndentWidth(currentRegion, _.otherIndentWidths += nextWidth)
    end handleNewLine

    def spaceTabMismatchMsg(lastWidth: IndentWidth, nextWidth: IndentWidth): Message =
      em"""Incompatible combinations of tabs and spaces in indentation prefixes.
          |Previous indent : $lastWidth
          |Latest indent   : $nextWidth"""

    /** Insert an <DEDENT> token if next token closes an indentation region.
     *  or next token is a comma and we expect a comma in an outer region.
     */
    def observeDedented(): Unit = currentRegion match
      case r: Indented
      if !r.isOutermost
          && (acceptDedentTokens.contains(token)
              || token == COMMA && r.outer.nn.commasExpectedInEnclosing)
          && next.token == EMPTY
        =>
          insert(DEDENT, offset)
      case _ =>

    def peekAhead() =
      prev.copyFrom(this)
      getNextToken(token)

    def reset() =
      next.copyFrom(this)
      this.copyFrom(prev)

    def closeIndented() = currentRegion match
      case r: Indented if !r.isOutermost => insert(DEDENT, offset)
      case _ =>

    def skipTrailingComma() =
      if closingParens.contains(currentRegion.closedBy) && currentRegion.commasExpected then
        peekAhead()
        if isAfterLineEnd && closingParens.contains(token) then
          // encountered a trailing comma
          // reset only the lastOffset
          // so that the tree's span is correct
          lastOffset = prev.lastOffset
        else
          reset()

    /** Is there a blank line between the current token and the last one?
     *  A blank line consists only of characters <= ' '.
     *  @pre  afterLineEnd().
     */
    private def pastBlankLine: Boolean = {
      val end = offset
      def recur(idx: Offset, isBlank: Boolean): Boolean =
        idx < end && {
          val ch = buf.charAt(idx)
          if (ch == LF || ch == FF) isBlank || recur(idx + 1, true)
          else recur(idx + 1, isBlank && ch <= ' ')
        }
      recur(lastOffset, false)
    }

    // f"\\u$c%04x" or f"${"\\"}u$c%04x"
    private def toUnicode(c: Char): String = { val s = c.toInt.toHexString; "\\u" + "0" * (4 - s.length) + s }

    inline def recognizeInterpolationId() =
      if (ch == '"' || ch == '\'') && token == IDENTIFIER then
        token = INTERPOLATIONID

    /** read next token, filling TokenData fields of Scanner.
     */
    protected final def fetchToken(): Unit = {
      offset = charOffset - 1
      lineOffset = if (lastOffset < lineStartOffset) lineStartOffset else -1
      (ch: @switch) match
        case ' ' | '\t' | CR | LF | FF =>
          nextChar()
          fetchToken()
        case 'A' | 'B' | 'C' | 'D' | 'E' |
             'F' | 'G' | 'H' | 'I' | 'J' |
             'K' | 'L' | 'M' | 'N' | 'O' |
             'P' | 'Q' | 'R' | 'S' | 'T' |
             'U' | 'V' | 'W' | 'X' | 'Y' |
             'Z' | '$' | '_' |
             'a' | 'b' | 'c' | 'd' | 'e' |
             'f' | 'g' | 'h' | 'i' | 'j' |
             'k' | 'l' | 'm' | 'n' | 'o' |
             'p' | 'q' | 'r' | 's' | 't' |
             'u' | 'v' | 'w' | 'x' | 'y' |
             'z' =>
          putChar(ch)
          nextChar()
          getIdentRest()
          recognizeInterpolationId()
        case '~' | '!' | '@' | '%' | '/' |
             '^' | '*' | '+' | '-' | /*'<' | */
             '<' | '>' | '?' | ':' | '=' | '&' |
             '|' | '\\' =>
          putChar(ch)
          nextChar()
          getOperatorRest()
        case '#' =>
          skipComment()
          fetchToken()
        case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
          getNumber()
        case '`' =>
          getBackquotedIdent()
        case '\"' | '\'' =>
          fetchString()
        case '.' =>
          nextChar()
          if '0' <= ch && ch <= '9' then
            putChar('.'); getFraction(); setStrVal()
          else
            token = DOT
        case ';' =>
          nextChar(); token = SEMI
        case ',' =>
          nextChar(); token = COMMA
        case '(' =>
          nextChar(); token = LPAREN
        case '{' =>
          nextChar(); token = LBRACE
        case ')' =>
          nextChar(); token = RPAREN
        case '}' =>
          currentRegion match
            case InBraces(InString(_, count, _)) if count >= 3 => nextRawChar()
            case _ => nextChar()
          token = RBRACE
        case '[' =>
          nextChar(); token = LBRACKET
        case ']' =>
          nextChar(); token = RBRACKET
        case SU =>
          if isAtEnd then
            token = EOF
          else
            error(em"illegal character")
            nextChar()
        case _ =>
          def fetchOther() =
            if isUnicodeIdentifierStart(ch) then
              putChar(ch)
              nextChar()
              getIdentRest()
              recognizeInterpolationId()
            else if isSpecial(ch) then
              putChar(ch)
              nextChar()
              getOperatorRest()
            else
              error(em"illegal character '${toUnicode(ch)}'")
              nextChar()
          fetchOther()
    }

    private def skipComment(): Unit =
      def appendToComment(ch: Char) =
        if keepComments then currentCommentBuf.append(ch)
      val start = lastCharOffset
      while
        appendToComment(ch)
        nextChar()
        ch != CR && ch != LF && ch != SU
      do ()
      if keepComments then
        val pos = Span(start, charOffset - 1, start)
        val comment = Comment(pos, currentCommentBuf.toString)
        currentCommentBuf.setLength(0)
        commentBuf += comment

// Lookahead ---------------------------------------------------------------

    /** The next token after this one.
     *  The token is computed via fetchToken, so complex two word
     *  tokens such as CASECLASS are not recognized.
     *  Newlines and indent/unindent tokens are skipped.
     *  Restriction: `lookahead` is illegal if the current token is INTERPOLATIONID
     */
    def lookahead: TokenData =
      if next.token == EMPTY then
        assert(token != INTERPOLATIONID)
          // INTERPOLATONIDs are followed by a string literal, which can set next
          // in peekAhead(). In that case, the following reset() would forget that token.
        peekAhead()
        reset()
      next

    class LookaheadScanner extends Scanner(source, offset) {
      override protected def initialCharBufferSize = 8
      override def languageImportContext = Scanner.this.languageImportContext
    }

    /** Skip matching pairs of `(...)` or `[...]` parentheses.
     *  @pre  The current token is `(` or `[`
     */
    final def skipParens(multiple: Boolean = true): Unit =
      val opening = token
      nextToken()
      while token != EOF && token != opening + 1 do
        if token == opening && multiple then skipParens() else nextToken()
      nextToken()

    /** Is the current token in a position where a modifier is allowed? */
    def inModifierPosition(): Boolean = {
      val lookahead = LookaheadScanner()
      while
        lookahead.nextToken()
        lookahead.token == NEWLINE || lookahead.isSoftModifier
      do ()
      modifierFollowers.contains(lookahead.token)
    }

// Identifiers ---------------------------------------------------------------

    private def getBackquotedIdent(): Unit = {
      nextChar()
      getLitChars('`')
      if (ch == '`') {
        nextChar()
        finishNamedToken(BACKQUOTED_IDENT)
        if (name.nn.length == 0)
          error(em"empty quoted identifier")
        else if (name.nn == nme.WILDCARD)
          error(em"wildcard invalid as backquoted identifier")
      }
      else error(em"unclosed quoted identifier")
    }

    @tailrec private def getIdentRest(): Unit = (ch: @switch) match {
      case 'A' | 'B' | 'C' | 'D' | 'E' |
           'F' | 'G' | 'H' | 'I' | 'J' |
           'K' | 'L' | 'M' | 'N' | 'O' |
           'P' | 'Q' | 'R' | 'S' | 'T' |
           'U' | 'V' | 'W' | 'X' | 'Y' |
           'Z' | '$' |
           'a' | 'b' | 'c' | 'd' | 'e' |
           'f' | 'g' | 'h' | 'i' | 'j' |
           'k' | 'l' | 'm' | 'n' | 'o' |
           'p' | 'q' | 'r' | 's' | 't' |
           'u' | 'v' | 'w' | 'x' | 'y' |
           'z' |
           '0' | '1' | '2' | '3' | '4' |
           '5' | '6' | '7' | '8' | '9' =>
        putChar(ch)
        nextChar()
        getIdentRest()
      case '_' =>
        putChar(ch)
        nextChar()
        getIdentOrOperatorRest()
      case SU => // strangely enough, Character.isUnicodeIdentifierPart(SU) returns true!
        finishNamed()
      case _ =>
        if isUnicodeIdentifierPart(ch) then
          putChar(ch)
          nextChar()
          getIdentRest()
        else
          finishNamed()
    }

    @tailrec private def getOperatorRest(): Unit = (ch: @switch) match
      case '~' | '!' | '@' | '%' | '/'
         | '^' | '*' | '+' | '-' | '<'
         | '>' | '?' | ':' | '=' | '&'
         | '|' | '\\' =>
        putChar(ch); nextChar(); getOperatorRest()
      case _ if isSpecial(ch) =>
        putChar(ch); nextChar(); getOperatorRest()
      case _ => finishNamed()

    private def getIdentOrOperatorRest(): Unit =
      if isIdentifierPart(ch) then getIdentRest() else getOperatorRest()

    def isSoftModifier: Boolean =
      token == IDENTIFIER && softModifierNames.contains(name.nn)

    def isSoftModifierInModifierPosition: Boolean =
      isSoftModifier && inModifierPosition()

    def isSoftModifierInParamModifierPosition: Boolean =
      isSoftModifier && lookahead.token != COLON

    /** Is current token the soft keyword `end` followed by a plainid? */
    def isEndMarker: Boolean =
      isIdent(nme.end) && lookahead.token == IDENTIFIER

// String Parsing -----------------------------------------------------------------

    def isRaw = token == INTERPOLATIONID && name.nn == nme.r

    private def unclosedStringLit(): Unit =
      error(em"unclosed string literal")
      // Recover as best we can by pretending the line has ended
      litBuf.setLength(0)
      adjustRegions(STRINGLIT)
      token = SEMI

    def multiline = delimCount >= 3

    def braceEscape(c: Char) =
      if (ch == '{' || ch == '}') && lookaheadRawChar() == ch then
        nextChar()

    def nextStrChar() =
      if isRaw then nextRawChar() else nextChar()

    private def getStringLit() =
      getLitChars(delimChar)
      if ch == delimChar then
        setStrVal()
        nextChar()
        token = STRINGLIT
      else
        unclosedStringLit()

    private def getMultilineStringLit(): Unit =
      if ch == delimChar then
        if getClosingQuote() then
          setStrVal()
          token = STRINGLIT
        else
          getMultilineStringLit()
      else if ch == SU then
        incompleteInputError(em"unclosed multi-line string literal")
      else
        getLitChar()
        getMultilineStringLit()

    // for interpolated strings
    @tailrec final def getStringPart(): Unit =
      if ch == delimChar then
        if getClosingQuote() then
          setStrVal()
          token = STRINGLIT
        else
          getStringPart()
      else if ch == SU then
        incompleteInputError(em"unclosed multi-line string literal")
      else if ch == '{' then
        if lookaheadRawChar() == '{' then
          nextChar()
          getLitChar()
          getStringPart()
        else
          setStrVal()
          token = STRINGPART
      else
        if ch == '}' && lookaheadRawChar() == '}' then
          nextChar()
        getLitChar()
        getStringPart()

    private def getClosingQuote(): Boolean =
      if multiline then
        nextStrChar()
        var seenQuotes = 1
        while seenQuotes < delimCount && ch == delimChar do
          seenQuotes += 1
          if seenQuotes == delimCount then nextChar() else nextStrChar()
        if seenQuotes >= delimCount then
          while ch == delimChar do
            putChar(delimChar)
            nextChar()
          true
        else
          while seenQuotes > 0 do
            putChar(delimChar)
            seenQuotes -= 1
          false
      else
        nextChar()
        true

    private def stringPart() =
      getStringPart()
      // don't edit the region if we recovered from a parsing error by inserting a semicolon
      if token != SEMI then
        currentRegion = InString(delimChar, delimCount, currentRegion)

    private def emptyString() =
      if delimChar == '\'' then
        error(em"empty character literal (use '\\'' for single quote)")
      else
        token = STRINGLIT
        strVal = ""

    def eatExtraQuotes() =
      delimCount = 3
      if delimChar == '\'' then
        while ch == delimChar do
          delimCount += 1
          nextChar()

    def isSpecString(): Boolean =
      var i = charOffset
      while i < endIdx && buf.charAt(i) == '\'' do
        i += 1
      Feature.magicEnabled
      && i + 4 < endIdx
      && i - charOffset >= 2
      && buf.charAt(i) == 's' && buf.charAt(i + 1) == 'p' && buf.charAt(i + 2) == 'e' && buf.charAt(i + 3) == 'c'
      && (isWhitespace(buf.charAt(i + 4)) || buf.charAt(i + 4) == LF)

    def fetchString() =
      delimChar = ch
      delimCount = 1
      if token == INTERPOLATIONID then
        nextStrChar()
        if ch == delimChar then
          if lookaheadRawChar() == delimChar then
            nextStrChar()
            nextStrChar()
            eatExtraQuotes()
            stringPart()
          else
            nextChar()
            emptyString()
        else
          stringPart()
      else if delimChar == '\'' && isSpecString() then
        token = INTERPOLATIONID
        name = nme.SPEC.asSimpleName
      else
        nextStrChar()
        if ch == delimChar then
          nextChar()
          if ch == delimChar then
            nextStrChar()
            eatExtraQuotes()
            getMultilineStringLit()
          else
            emptyString()
        else
          getStringLit()

    def fetchStringPart(r: InString) =
      delimChar = r.delimChar
      delimCount = r.delimCount
      offset = charOffset - 1
      getStringPart()

// Literals -----------------------------------------------------------------

    /** Copy current character into cbuf, interpreting any escape sequences,
     *  and advance to next character.
     */
    protected def getLitChar(): Unit =
      if ch == '\\' then
        nextChar()
        charEscape()
      else
        putChar(ch)
        nextStrChar()

    private def nextHexDigit(): Int =
      nextStrChar()
      digit2int(ch, 16)

    private def charEscape(): Unit =
      ch match
        case 'x'  => hexEscape(2)
        case 'u'  => hexEscape(4)
        case 'U'  => hexEscape(8)
        case _ => ch match
          case '\"' => putChar('\"')
          case '\'' => putChar('\'')
          case '\\' => putChar('\\')
          case 'a'  => putChar(0x07)
          case 'b'  => putChar('\b')
          case 'f'  => putChar('\f')
          case 'n'  => putChar('\n')
          case 'r'  => putChar('\r')
          case 't'  => putChar('\t')
          case 'v'  => putChar(0x0B)
          case _    => error(em"invalid escape character", charOffset - 1)
          nextChar()

    private def hexEscape(count: Int): Unit =
      var n = count
      var cp = 0
      nextStrChar()
      var digit = digit2int(ch, 16)
      while count > 0 && digit >= 0 do
        cp = cp << 4 | ch
        nextStrChar()
        digit = digit2int(ch, 16)
        n -= 1
      if n > 0 then
        val unicode = if count == 2 then "" else "unicode "
        error(em"invalid character in ${unicode}escape sequence", charOffset - 1)
      putChar(cp.asInstanceOf[Char])

    private def getLitChars(delimiter: Char) =
      while (ch != delimiter && !isAtEnd && (ch != SU && ch != CR && ch != LF || isUnicodeEscape))
        getLitChar()

    private def getDigits(base: Int): Unit =
      while digit2int(ch, base) >= 0 || isNumberSeparator(ch) do
        putChar(ch)
        nextChar()
      checkNoTrailingSeparator()

    /** read fractional part and exponent of floating point number
     *  if one is present.
     */
    protected def getFraction(): Unit =
      token = DECILIT
      getDigits(10)
      if ch == 'e' || ch == 'E' then
        putChar(ch)
        nextChar()
        if ch == '+' || ch == '-' then
          putChar(ch)
          nextChar()
        if '0' <= ch && ch <= '9' then
          getDigits(10)
        else
          error(em"Invalid floating point number")
        token = EXPOLIT

    /** Read a number into strVal and set base and token.
     *  @pre:  ch in 0..9
     */
    def getNumber(): Unit =
      base = 10
      if ch == '0' then
        lookaheadRawChar() match
          case 'x' | 'X' => base = 16; nextChar(); nextChar()
          case 'b' | 'B' => base =  2; nextChar(); nextChar()
          case _ =>
      getDigits(base)
      if litBuf.isEmpty then error(em"invalid number literal")
      token = INTLIT
      if ch == '.' && base == 10 && digit2int(lookaheadRawChar(), 10) >= 0 then
        putChar('.')
        nextChar()
        getFraction()
      else if (ch == 'e' || ch == 'E') && base == 10 then
        getFraction()
      if (isIdentifierPart(ch) && ch >= ' ')
        error(em"Invalid literal number")
      setStrVal()

    override def toString: String =
      showTokenDetailed(token) + {
        if identifierTokens.contains(token) then s" $name"
        else if literalTokens.contains(token) then s" $strVal"
        else ""
      }

    def show: String = token match
      case IDENTIFIER | BACKQUOTED_IDENT => s"id($name)"
      case INTLIT => s"int($strVal, base = $base)"
      case DECILIT => s"float($strVal)"
      case EXPOLIT => s"double($strVal)"
      case STRINGLIT => s"string($strVal)"
      case STRINGPART => s"stringpart($strVal)"
      case INTERPOLATIONID => s"interpolationid($name)"
      case NEWLINE => "';'"
      case _ =>
        if debugTokenStream then showTokenDetailed(token) else showToken(token)

   /* Initialization: read first char, then first token */
    nextChar()
    nextToken()
    currentRegion = topLevelRegion(indentWidth(offset))
  end Scanner

  /** A Region indicates what encloses the current token. It can be one of the following
   *
   *   InString    a string interpolation
   *   InParens    a pair of parentheses (...) or brackets [...]
   *   InBraces    a pair of braces { ... }
   *   Indented    a pair of <indent> ... <DEDENT> tokens
   *   InSuite     the rest of a line following a `:`
   */
  abstract class Region(val closedBy: Token):

   /** The region enclosing this one, or `null` for the outermost region */
    def outer: Region | Null

    /** Is this region the outermost region? */
    def isOutermost = outer == null

    /** The enclosing region, which is required to exist */
    def enclosing: Region = outer.asInstanceOf[Region]

    def isClosedBy(tokens: Token*): Boolean = tokens.exists(closedBy == _)

    var knownWidth: IndentWidth | Null = null

    /** The indentation width, Zero if not known */
    final def indentWidth: IndentWidth =
      val known = knownWidth
      if known == null then IndentWidth.Zero else known

    def proposeKnownWidth(width: IndentWidth, lastToken: Token) =
      if knownWidth == null then
        this match
          case InParens(_, _) if lastToken != LPAREN =>
            useOuterWidth()
          case _ =>
            knownWidth = width

    private def useOuterWidth(): Unit =
      if enclosing.knownWidth == null then enclosing.useOuterWidth()
      knownWidth = enclosing.knownWidth

    /** Does `width` represent an undent of an enclosing indentation region?
     *  This is the case if there is an indentation region that goes deeper than `width`
     *  and that is enclosed in a region that contains `width` as an indentation width.
     */
    def isClosedByUndentAt(width: IndentWidth): Boolean = this match
      case _: Indented =>
        !isOutermost && width <= indentWidth && enclosing.coversIndent(width)
      case _ =>
        enclosing.isClosedByUndentAt(width)

    /** A region "covers" an indentation with `width` if it has `width` as known
     *  indentation width (either as primary, or in case of an Indent region as
     *  alternate width).
     */
    protected def coversIndent(w: IndentWidth): Boolean =
      knownWidth != null && w == indentWidth

    private var myCommasExpected: Boolean = false

    inline def withCommasExpected[T](inline op: => T): T =
      val saved = myCommasExpected
      myCommasExpected = true
      val res = op
      myCommasExpected = false
      res

    def commasExpected = myCommasExpected

    def commasExpectedInEnclosing: Boolean =
      commasExpected || this.match
        case r: Indented => !r.isOutermost && r.outer.nn.commasExpectedInEnclosing
        case _ => false

    final def findEnclosing(p: Region => Boolean): Region | Null =
      if p(this) then this
      else if outer == null then null
      else outer.nn.findEnclosing(p)

    final def existsEnclosing(p: Region => Boolean): Boolean =
      findEnclosing(p) != null

    def toList: List[Region] =
      val outer = this.outer
      this :: (if outer == null then Nil else outer.toList)

    private def delimiter = this match
      case _: InString => "}(in string)"
      case InParens(LPAREN, _) => ")"
      case InParens(LBRACKET, _) => "]"
      case _: InBraces => "}"
      case _: Indented => "DEDENT"
      case _: InSuite => "end of single-line suite"

    /** Show open regions as list of lines with decreasing indentations */
    def visualize: String =
      toList.map(r => s"${r.indentWidth.toPrefix}${r.delimiter}").mkString("\n")

    override def toString: String =
      toList.map(r => s"(${r.indentWidth}, ${r.delimiter})").mkString(" in ")
  end Region

  case class InString(delimChar: Char, delimCount: Int, outer: Region) extends Region(RBRACE)
  case class InParens(prefix: Token, outer: Region) extends Region(prefix + 1)
  case class InBraces(outer: Region) extends Region(RBRACE)
  case class BeforeColon(outer: Region) extends Region(COLON)
  case class InSuite(outer: Region) extends Region(ENDsuite)

  object BeforeColon extends (Region => Region)
  object InSuite extends (Region => Region)

  /** A class describing an indentation region.
   *  @param width   The principal indentation width
   *  @param prefix  The token before the initial <indent> of the region
   */
  case class Indented(width: IndentWidth, prefix: Token, outer: Region | Null) extends Region(DEDENT):
    knownWidth = width

    /** Other indendation widths > width of lines in the same region */
    var otherIndentWidths: Set[IndentWidth] = Set()

    override def coversIndent(w: IndentWidth) = width == w || otherIndentWidths.contains(w)
  end Indented

  def topLevelRegion(width: IndentWidth) = Indented(width, EMPTY, null)

  enum IndentWidth {
    case Run(ch: Char, n: Int)
    case Conc(l: IndentWidth, r: Run)

    def <= (that: IndentWidth): Boolean = this match {
      case Run(ch1, n1) =>
        that match {
          case Run(ch2, n2) => n1 <= n2 && (ch1 == ch2 || n1 == 0)
          case Conc(l, r) => this <= l
        }
      case Conc(l1, r1) =>
        that match {
          case Conc(l2, r2) => l1 == l2 && r1 <= r2
          case _ => false
        }
    }

    def < (that: IndentWidth): Boolean = this <= that && !(that <= this)

    final def advance(buf: String, start: Int): Int = this match
      case Run(ch, n) =>
        if start + n > buf.length then -1
        else
          var i = 0
          while i < n && buf.charAt(start + i) == ch do i += 1
          if i < n then -1 else n
      case Conc(w1, w2) =>
        val len1 = w1.advance(buf, start)
        if len1 < 0 then len1
        else w2.advance(buf, start + len1)

    /** Does `this` differ from `that` by not more than a single space? */
    def isClose(that: IndentWidth): Boolean = this match
      case Run(ch1, n1) =>
        that match
          case Run(ch2, n2) => ch1 == ch2 && ch1 != '\t' && (n1 - n2).abs <= 1
          case Conc(l, r) => false
      case Conc(l1, r1) =>
        that match
          case Conc(l2, r2) => l1 == l2 && r1.isClose(r2)
          case _ => false

    def toPrefix: String = this match {
      case Run(ch, n) => ch.toString * n
      case Conc(l, r) => l.toPrefix + r.toPrefix
    }

    def toPrefixSize: Int = this match {
      case Run(ch, n) => n
      case Conc(l, r) => l.toPrefixSize + r.toPrefixSize
    }

    override def toString: String = {
      def kind(ch: Char) = ch match {
        case ' ' => "space"
        case '\t' => "tab"
        case _ => s"'$ch'-character"
      }
      this match {
        case Run(ch, n) => s"$n ${kind(ch)}${if (n == 1) "" else "s"}"
        case Conc(l, r) => s"$l, $r"
      }
    }
  }

  object IndentWidth {
    private inline val MaxCached = 40
    private val spaces = Array.tabulate(MaxCached + 1)(new Run(' ', _))
    private val tabs = Array.tabulate(MaxCached + 1)(new Run('\t', _))

    def Run(ch: Char, n: Int): Run =
      if (n <= MaxCached && ch == ' ') spaces(n)
      else if (n <= MaxCached && ch == '\t') tabs(n)
      else new Run(ch, n)

    val Zero = Run(' ', 0)
  }

  // ------------- keyword configuration -----------------------------------

  private val kwMap = buildKeywordMap(keywords)
}
