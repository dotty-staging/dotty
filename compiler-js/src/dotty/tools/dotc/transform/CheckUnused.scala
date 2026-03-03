package dotty.tools.dotc
package transform

import core.*
import core.Names.Name
import core.StdNames.nme
import Contexts.*
import ast.tpd
import ast.untpd
import DenotTransformers.IdentityDenotTransformer
import Phases.Phase
import MegaPhase.MiniPhase

/** Stub: CheckUnused is disabled on Scala.js (uses source content for code actions). */
class CheckUnused private (phaseMode: CheckUnused.PhaseMode, suffix: String) extends MiniPhase:
  import CheckUnused.*

  override def phaseName: String = s"checkUnused$suffix"
  override def description: String = "check for unused elements (disabled on JS)"
  override def isEnabled(using Context): Boolean = false

object CheckUnused:
  enum PhaseMode:
    case Aggregate, Resolve, Report

  val OriginalName = dotty.tools.dotc.util.Property.StickyKey[Name]

  def PostTyper() = CheckUnused(PhaseMode.Aggregate, "PostTyper")
  def PostInlining() = CheckUnused(PhaseMode.Resolve, "PostInlining")
  def PostPatMat() = CheckUnused(PhaseMode.Report, "PostPatMat")
