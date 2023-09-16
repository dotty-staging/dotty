package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Contexts.*, Annotations.*, Flags.*
import ast.{tpd, untpd}
import Decorators.*, NameOps.*
import config.SourceVersion
import config.Printers.capt
import util.Property.Key
import tpd.*
import config.Feature
import MutableCaptures.*

extension (t: Tree)
  /** Extract the elememts of the separation degree specified by a @sep annotation */
  def sepDegreeElems(using Context): List[Tree] = t match
    case Apply(_, Typed(SeqLiteral(elems, _), _) :: Nil) => elems
    case _ => Nil

object SepDegree:
  def ofType(tp: Type)(using Context): CaptureSet =
    def recur(tp: Type): CaptureSet =
      tp.dealias match
        case WithSepDegree(parent, refs) => recur(parent) ++ refs
        case _: TypeRef => CaptureSet.empty
        case _: TypeParamRef => CaptureSet.empty
        case tp: TypeProxy => recur(tp.underlying)
        case AndType(tp1, tp2) => recur(tp1) ++ recur(tp2)
        case OrType(tp1, tp2) => recur(tp1) ** recur(tp2)
        case _ => CaptureSet.empty
    recur(tp)

object WithSepDegree:
  def unapply(tp: AnnotatedType)(using Context): Option[(Type, CaptureSet)] =
    if ctx.phase == Phases.checkCapturesPhase && !ctx.mode.is(Mode.IgnoreCaptures) then
      tp.annot match
        case ann: SepAnnotation => Some((tp.parent, ann.refs))
        case ann if ann.symbol == defn.SepAnnot =>
          try Some((tp.parent, ann.tree.toCaptureSet))
          catch case ex: IllegalCaptureRef => None
        case _ => None
    else None

  def apply(parent: Type, refs: CaptureSet)(using Context): Type =
    if refs.isAlwaysEmpty then parent
    else AnnotatedType(parent, SepAnnotation(refs)(defn.SepAnnot))

extension (tp: Type)
  def derivedWithSepDegree(parent: Type, refs: CaptureSet)(using Context): Type = tp match
    case WithSepDegree(p, r) =>
      if (parent eq p) && (refs eq r) then tp else WithSepDegree(parent, refs)

  def sepDegree(using Context): CaptureSet = SepDegree.ofType(tp)

extension (ref: CaptureRef)
  def isReader(using Context): Boolean = ref match
    case MutableRef(_, isRead) => isRead
    case _ => false
