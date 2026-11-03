package dotty.tools
package dotc
package magic

import collection.immutable.BitSet
import core.Decorators.*
import core.StdNames.nme
import dotty.tools.dotc.core.Names.*

object Tokens:

  type Token = Int
  type TokenSet = BitSet

  val tokenString, debugString: Array[String | Null] = new Array[String | Null](maxToken + 1)

  def enter(token: Token, str: String, debugStr: String = ""): Unit = {
    assert(tokenString(token) == null, token)
    tokenString(token) = str
    debugString(token) = if (debugStr.isEmpty) str else debugStr
  }

  /** special tokens */
  inline val minToken = EMPTY
  inline val EMPTY = 0;             enter(EMPTY, "<empty>") // a missing token, used in lookahead
  inline val ERROR = 1;             enter(ERROR, "erroneous token") // an erroneous token
  inline val EOF = 2;               enter(EOF, "eof")

  /** literals */
  inline val INTLIT = 4;            enter(INTLIT, "integer literal")
  inline val DECILIT = 5;           enter(DECILIT, "decimal literal")
  inline val EXPOLIT = 6;           enter(EXPOLIT, "floating point literal")
  inline val STRINGLIT = 7;         enter(STRINGLIT, "string literal")
  inline val STRINGPART = 8;        enter(STRINGPART, "string literal", "string literal part")
  inline val INTERPOLATIONID = 9;   enter(INTERPOLATIONID, "string interpolator")

  /** identifiers */
  inline val IDENTIFIER = 10;       enter(IDENTIFIER, "identifier")
  inline val BACKQUOTED_IDENT = 11; enter(BACKQUOTED_IDENT, "identifier", "backquoted ident")

  /** special symbols */
  inline val COMMA = 13;            enter(COMMA, "','")
  inline val SEMI = 14;             enter(SEMI, "';'")
  inline val DOT = 15;              enter(DOT, "'.'")
  inline val NEWLINE = 16;          enter(NEWLINE, "end of statement", "new line")
  inline val ENDsuite = 17;         enter(ENDsuite, "end of single-line lambda")

  /** alphabetic keywords */
  inline val firstAlphaKW = AND
  inline val AND = 20;              enter(AND, "and")
  inline val AS = 21;               enter(AS, "as")
  inline val BREAK = 22;            enter(BREAK, "break")
  inline val CASE = 23;             enter(CASE, "case")
  inline val CLASS = 24;            enter(CLASS, "class")
  inline val CONTINUE = 25;         enter(CONTINUE, "continue")
  inline val DEF = 26;              enter(DEF, "def")
  inline val DERIVES = 27;          enter(DERIVES, "derives")
  inline val ELIF = 28;             enter(ELIF, "elif")
  inline val ELSE = 29;             enter(ELSE, "else")
  inline val ENUM = 30;             enter(ENUM, "enum")
  inline val EXCEPT = 31;           enter(EXCEPT, "except")
  inline val EXPORT = 32;           enter(EXPORT, "export")
  inline val EXTENDS = 33;          enter(EXTENDS, "extends")
  inline val EXTENSION = 34;        enter(EXTENSION, "extension")
  inline val FALSE = 35;            enter(FALSE, "False")
  inline val FINALLY = 36;          enter(FINALLY, "finally")
  inline val FOR = 37;              enter(FOR, "for")
  inline val FROM = 38;             enter(FROM, "from")
  inline val IF = 39;               enter(IF, "if")
  inline val IN = 40;               enter(IN, "in")
  inline val IS = 41;               enter(IS, "is")
  inline val IMPORT = 42;           enter(IMPORT, "import")
  inline val MATCH = 43;            enter(MATCH, "match")
  inline val NEW = 44;              enter(NEW, "new")
  inline val NOT = 45;              enter(NOT, "not")
  inline val NONE = 46;             enter(NONE, "None")
  inline val OBJECT = 47;           enter(OBJECT, "object")
  inline val OR = 48;               enter(OR, "or")
  inline val PRIVATE = 49;          enter(PRIVATE, "private")
  inline val RAISE = 50;            enter(RAISE, "raise")
  inline val RETURN = 51;           enter(RETURN, "return")
  inline val SELF = 52;             enter(SELF, "self")
  inline val SUPER = 53;            enter(SUPER, "super")
  inline val TRAIT = 54;            enter(TRAIT, "trait")
  inline val TRUE = 55;             enter(TRUE, "True")
  inline val TRY = 56;              enter(TRY, "try")
  inline val TYPE = 57;             enter(TYPE, "type")
  inline val USES = 58;             enter(USES, "uses")
  inline val USING = 59;            enter(USING, "using")
  inline val VAL = 60;              enter(VAL, "val")
  inline val VAR = 61;              enter(VAR, "var")
  inline val WHILE = 62;            enter(WHILE, "while")
  inline val WITH = 63;             enter(WITH, "with")
  inline val lastAlphaKW = WITH

  /** symbolic keywords */
  inline val firstSymbolKW = COLON
  inline val COLON = 68;            enter(COLON, ":")
  inline val EQUALS = 69;           enter(EQUALS, "=")
  inline val THINARROW = 70;        enter(THINARROW, "->")
  inline val ARROW = 71;            enter(ARROW, "=>")
  inline val THINCTXARROW = 72;     enter(THINCTXARROW, "?->")
  inline val CTXARROW = 73;         enter(CTXARROW, "?=>")
  inline val SUBTYPE = 74;          enter(SUBTYPE, "<:")
  inline val SUPERTYPE = 75;        enter(SUPERTYPE, ">:")
  inline val ASSIGN = 76;           enter(ASSIGN, ":=")
  inline val USCORE = 77;           enter(USCORE, "_")
  inline val AT = 78;               enter(AT, "@")
  inline val lastSymbolKW = AT

  /** parentheses */
  inline val firstParen = LPAREN
  inline val LPAREN = 80;           enter(LPAREN, "'('")
  inline val RPAREN = 81;           enter(RPAREN, "')'")
  inline val LBRACKET = 82;         enter(LBRACKET, "'['")
  inline val RBRACKET = 83;         enter(RBRACKET, "']'")
  inline val LBRACE = 84;           enter(LBRACE, "'{'")
  inline val RBRACE = 85;           enter(RBRACE, "'}'")
  inline val INDENT = 86;           enter(INDENT, "indent")
  inline val DEDENT = 87;           enter(DEDENT, "dedent")
  inline val lastParen = DEDENT
  inline val maxToken = lastParen

  def buildKeywordMap(keywords: TokenSet): Map[SimpleName, Token] =
    val pairs =
      for
        kw <- keywords.iterator
        ts = tokenString(kw)
        if ts != null && !ts.contains(' ')
      yield
        (termName(ts.nn), kw)
    pairs.toMap

  def tokenRange(lo: Int, hi: Int): TokenSet = BitSet(lo to hi *)

  final val alphaKeywords: TokenSet = tokenRange(firstAlphaKW, lastAlphaKW)
  final val symbolicKeywords: TokenSet = tokenRange(firstSymbolKW, lastSymbolKW)
  final val keywords: TokenSet = alphaKeywords | symbolicKeywords

  def isKeyword(token: Token): Boolean = keywords.contains(token)

  final val identifierTokens: TokenSet = BitSet(IDENTIFIER, BACKQUOTED_IDENT)

  def isIdentifier(token : Int): Boolean =
    token >= IDENTIFIER && token <= BACKQUOTED_IDENT

  final val allTokens: TokenSet = tokenRange(minToken, maxToken)

  final val simpleLiteralTokens: TokenSet =
    tokenRange(INTLIT, STRINGLIT) | BitSet(TRUE, FALSE, NONE)

  final val literalTokens: TokenSet =
    simpleLiteralTokens | BitSet(INTERPOLATIONID)

  final val atomicExprTokens: TokenSet =
    literalTokens | identifierTokens | BitSet(SELF, SUPER, RETURN)

  final val openParensTokens = BitSet(LBRACE, LPAREN, LBRACKET)

  final val canStartInfixExprTokens =
    atomicExprTokens | openParensTokens | BitSet(NEW)

  final val canStartExprTokens: TokenSet =
    canStartInfixExprTokens | BitSet(INDENT, IF, WHILE, FOR, TRY, RAISE, WITH, MATCH)

  final val canStartTypeTokens: TokenSet =
    identifierTokens | BitSet(SELF, SUPER, LPAREN, LBRACE)

  final val canStartTemplateTokens: TokenSet =
    BitSet(EXTENDS, DERIVES, USES, COLON)

  final val canStartInfixExprOrTypeTokens = canStartInfixExprTokens | canStartTypeTokens

  final val canStartPatternTokens =
    literalTokens | identifierTokens | openParensTokens

  final val dclIntroTokens: TokenSet = BitSet(DEF, VAL, VAR, TYPE)

  final val defIntroTokens: TokenSet = dclIntroTokens | BitSet(PRIVATE, AT, CLASS, TRAIT, OBJECT, ENUM)

  final val arrowTokens: TokenSet = BitSet(ARROW, CTXARROW, THINARROW, THINCTXARROW)

  final val modifierTokens: TokenSet = BitSet(PRIVATE)

  final val modifierTokensOrCase: TokenSet = modifierTokens | BitSet(CASE)

  final val modifierFollowers = modifierTokensOrCase | defIntroTokens

  final val varargFollowers = BitSet(RPAREN, COMMA, EOF)

  /** Is token only legal as start of statement (eof also included)? */
  final val mustStartStatTokens: TokenSet = defIntroTokens | modifierTokens | BitSet(IMPORT, EXPORT)

  final val canEndStatSeqTokens =
    BitSet(DEDENT, EOF, RPAREN, RBRACE, RBRACKET, ELIF, ELSE, EXCEPT, FINALLY)

  final val canStartStatTokens: TokenSet =
    canStartExprTokens | defIntroTokens | modifierTokens
    | BitSet(AT, CASE, IMPORT, EXPORT, BREAK, CONTINUE, EXTENSION)

  final val canEndStatTokens: TokenSet =
      atomicExprTokens | BitSet(TYPE, RPAREN, RBRACE, RBRACKET, DEDENT, ENDsuite)

  final val numericLitTokens: TokenSet = BitSet(INTLIT, DECILIT, EXPOLIT)

  final val statCtdTokens: BitSet = BitSet(COLON, ELSE, ELIF, EXCEPT, FINALLY)

  final val closingRegionTokens = BitSet(RBRACE, RPAREN, RBRACKET, CASE) | statCtdTokens

  final val acceptDedentTokens = BitSet(RBRACE, RPAREN, RBRACKET) | statCtdTokens

  final val canStartIndentTokens: BitSet =
    BitSet(COLON, EQUALS, ARROW, CTXARROW)

  /** Faced with the choice between a type and a formal parameter, the following
   *  tokens determine it's a formal parameter.
   */
  final val startParamTokens: BitSet = modifierTokens | BitSet(VAL, VAR, AT)

  final val canStartCapSetContentsTokens = BitSet(IDENTIFIER, BACKQUOTED_IDENT, SELF, RBRACE)

  final val closingParens = BitSet(RPAREN, RBRACKET, RBRACE)

  final val softModifierNames = Set(
    nme.consume, nme.CASEkw, nme.erased, nme.infix,
    nme.inline, nme.LAZYkw, nme.opaque, nme.open, nme.OVERRIDEkw,
    nme.SEALEDkw, nme.tracked, nme.transparent, nme.update)

  final val symbolicCompareOperatorNames = Set(
    nme.LT, nme.LE, nme.EQ, nme.NE, nme.GT, nme.GE)

  final val paramModifierNames = Set(
    nme.consume, nme.erased, nme.inline)

  final val prefixOpNames = Set(
    nme.raw.MINUS, nme.raw.PLUS, nme.raw.TILDE)

  def showTokenDetailed(token: Int): String = debugString(token).nn

  def showToken(token: Int): String =
    val str = tokenString(token).nn
    if isKeyword(token) then s"'$str'" else str
end Tokens