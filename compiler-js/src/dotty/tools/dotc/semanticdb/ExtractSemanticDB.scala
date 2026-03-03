package dotty.tools
package dotc
package semanticdb

import scala.language.unsafeNulls

import core.*
import Phases.*
import ast.tpd.*
import ast.Trees.{mods, WithEndMarker}
import Contexts.*
import Symbols.*
import Flags.*
import Names.Name
import Types._
import StdNames.{nme, tpnme}
import NameOps.*
import Denotations.StaleSymbol
import util.Spans.Span
import util.SourceFile

import scala.collection.mutable
import scala.annotation.{ threadUnsafe => tu, tailrec }
import scala.jdk.CollectionConverters.*
import scala.PartialFunction.condOpt
import typer.ImportInfo.withRootImports

import dotty.tools.dotc.{semanticdb => s}
import dotty.tools.io.{AbstractFile, JarArchive}
import dotty.tools.dotc.semanticdb.DiagnosticOps.*

/** Stub ExtractSemanticDB for Scala.js - SemanticDB extraction not needed for MVP */
class ExtractSemanticDB extends Phase:
  override def phaseName: String = ExtractSemanticDB.name
  override def description: String = ExtractSemanticDB.description

  override def isRunnable(using Context): Boolean = false

  override def run(using Context): Unit = ()
end ExtractSemanticDB

object ExtractSemanticDB:
  val name: String = "extractSemanticDB"
  val description: String = "extract semanticDB info (disabled on Scala.js)"

  /** Stub type aliases used by semanticdb package object */
  type ExtractSemanticInfo = ExtractSemanticDB
  type AppendDiagnostics = ExtractSemanticDB

  /** Stub Extractor class */
  class Extractor:
    import ast.tpd.*
    val symbolInfos: scala.collection.mutable.ListBuffer[SymbolInformation] = scala.collection.mutable.ListBuffer.empty
    val occurrences: scala.collection.mutable.ListBuffer[SymbolOccurrence] = scala.collection.mutable.ListBuffer.empty
    def traverse(tree: Tree)(using Context): Unit = ()
