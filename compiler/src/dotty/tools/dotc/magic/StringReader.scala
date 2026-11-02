package dotty.tools
package dotc
package magic

import util.Chars.*

import scala.compiletime.uninitialized

abstract class StringReader { self =>

  val buf: String
  val endIdx: Int  // up to where `buf` should be read

  protected def startFrom: Int = 0

  /** the last read character */
  var ch: Char = uninitialized

  /** The offset one past the last read character */
  var charOffset: Int = startFrom

  /** The offset before the last read character */
  var lastCharOffset: Int = startFrom

  /** The start offset of the current line */
  var lineStartOffset: Int = startFrom

  private var lastUnicodeOffset = -1

  /** Is last character a unicode escape \\uxxxx? */
  def isUnicodeEscape: Boolean = charOffset == lastUnicodeOffset

  /** Advance one character; reducing CR;LF pairs to just LF */
  final def nextChar(): Unit =
    nextRawChar()
    if ch == CR && charOffset < endIdx && buf.charAt(charOffset) == LF then
      charOffset += 1
      ch = LF

  /** Advance one character, no CR;LF reduction */
  def nextRawChar(): Unit =
    val idx = charOffset
    lastCharOffset = idx
    charOffset = idx + 1
    ch = if idx >= endIdx then SU else buf.charAt(idx)

  def getc(): Char = { nextChar() ; ch }


  /** Handle line ends */
  private def potentialLineEnd(): Unit =
    if (ch == LF || ch == FF) lineStartOffset = charOffset

  def isAtEnd: Boolean = charOffset >= endIdx

  /** A new reader that takes off at the current character position */
  def lookaheadReader(): StringLookaheadReader = new StringLookaheadReader

  def lookaheadChar(): Char = lookaheadReader().getc()

  def lookaheadRawChar(): Char =
    if charOffset < endIdx then buf.charAt(charOffset) else SU

  class StringLookaheadReader extends StringReader {
    val buf: String = self.buf
    val endIdx = self.endIdx
    charOffset = self.charOffset
    ch = self.ch
  }
}
