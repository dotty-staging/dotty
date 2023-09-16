package dotty.tools
package dotc
package cc

import core.*
import Phases.*, DenotTransformers.*, SymDenotations.*
import Contexts.*, Names.*, Flags.*, Symbols.*, Decorators.*
import Types.*, StdNames.*, Denotations.*
import config.Printers.{capt, recheckr}
import config.{Config, Feature}
import ast.{tpd, Trees}
import Trees.*
import tpd.*
import typer.RefChecks.{checkAllOverrides, checkSelfAgainstParents, OverridingPairsChecker}
import typer.Checking.{checkBounds, checkAppliedTypesIn}
import util.{SimpleIdentitySet, EqHashMap, SrcPos, Property}
import transform.SymUtils.*
import transform.{Recheck, PreRecheck}
import Recheck.*
import scala.collection.mutable
import CaptureSet.{withCaptureSetsExplained, IdempotentCaptRefMap}
import StdNames.nme
import NameKinds.DefaultGetterName
import reporting.trace
import MutableCaptures.*

object SepCheck:
  def isSeparated(ref1: CaptureRef, ref2: CaptureRef, frozen: Boolean)(using Context): Boolean =
    def tryDegree(r1: CaptureRef, r2: CaptureRef): Boolean =
      val deg = r1.sepDegree
      !deg.isAlwaysEmpty && r2.singletonCaptureSet.subCaptures(deg, frozen = frozen).isOK

    def tryWiden(r1: CaptureRef, r2: CaptureRef): Boolean =
      val w1 = r1.captureSetOfInfo
      !w1.isUniversal && isSeparated(w1, r2.singletonCaptureSet, frozen = frozen)

    def tryVars = (ref1, ref2) match
      case (MutableRef(sym1, _), MutableRef(sym2, _)) => sym1 ne sym2
      case _ => false

    def tryReaders = ref1.isReader && ref2.isReader

    tryReaders || tryVars || tryDegree(ref1, ref2) || tryDegree(ref2, ref1) || tryWiden(ref1, ref2) || tryWiden(ref2, ref1)

  def isSeparated(cs1: CaptureSet, cs2: CaptureSet, frozen: Boolean)(using Context): Boolean =
    cs1.isAlwaysEmpty || cs2.isAlwaysEmpty
    || cs1.elems.forall: ref1 =>
         cs2.elems.forall: ref2 =>
           ref1.separateFrom(ref2, frozen)

  extension (ref: CaptureRef)
    def separateFrom(other: CaptureRef, frozen: Boolean)(using Context): Boolean = isSeparated(ref, other, frozen = frozen)

  def checkWellformed(ann: tpd.Tree, srcPos: SrcPos)(using Context): Unit =
    ann.sepDegreeElems.foreach: ref =>
      ref.tpe match
        case tp: TermRef if tp.symbol == defn.captureRoot =>
          report.error(em"$ref is not a legal element of a separation degree", srcPos)
        case _ =>  // FIXME: reject reader root too

