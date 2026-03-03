package dotty.tools.dotc
package transform

import scala.language.unsafeNulls

import ast.tpd
import ast.Trees.*
import core.Annotations.*
import core.Contexts.*
import core.Decorators.*
import core.DenotTransformers.DenotTransformer
import core.Flags.*
import core.MacroClassLoader
import core.Symbols.*
import core.Types.*
import quoted.*
import util.SrcPos

import scala.util.control.NonFatal

/** Stub MacroAnnotations for Scala.js - macros are not supported */
object MacroAnnotations:

  import tpd.*

  extension (annot: Annotation)
    def isMacroAnnotation(using Context): Boolean =
      annot.tree.symbol.maybeOwner.derivesFrom(defn.MacroAnnotationClass)
  end extension

  extension (sym: Symbol)
    def hasMacroAnnotation(using Context): Boolean =
      sym.getAnnotation(defn.MacroAnnotationClass).isDefined
  end extension

  def expandAnnotations(tree: MemberDef, companion: Option[MemberDef])(using Context): (List[MemberDef], Option[MemberDef]) =
    if !tree.symbol.hasMacroAnnotation then
      (List(tree), companion)
    else
      report.error(em"Macro annotations are not supported on Scala.js", tree.srcPos)
      (List(tree), companion)

  def enterMissingSymbols(tree: MemberDef, trans: DenotTransformer)(using Context): Unit =
    // No-op on Scala.js - macro annotations not supported
    ()

end MacroAnnotations
