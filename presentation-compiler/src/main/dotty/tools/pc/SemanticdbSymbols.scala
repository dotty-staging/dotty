package dotty.tools.pc

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.semanticdb

object SemanticdbSymbols:

  def inverseSemanticdbSymbol(sym: String)(using ctx: Context): List[Symbol] =
    semanticdb.inverseSymbol(sym)

  /** The semanticdb name of the given symbol */
  def symbolName(sym: Symbol)(using Context): String =
    semanticdb.symbolName(sym)
