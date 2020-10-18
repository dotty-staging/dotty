package dotty.tools
package backend.jvm

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Phases._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Flags.Trait
import dotty.tools.dotc.transform.MegaPhase.MiniPhase

/** Collect all super calls to trait members.
 *
 *  For each super reference to trait member, register a call from the current class to the
 *  owner of the referenced member.
 *
 *  This information is used to know if it is safe to remove a redundant mixin class.
 *  A redundant mixin class is one that is implemented by another mixin class. As the
 *  methods in a redundant mixin class could be implemented with a default abstract method,
 *  the redundant mixin class could be required as a parent by the JVM.
 */
class CollectSuperCalls extends MiniPhase {
  import tpd._

  def phaseName: String = "collectSuperCalls"

  override def transformSelect(tree: Select)(using Context): Tree = {
    tree.qualifier match {
      case sup: Super =>
        if (tree.symbol.owner.is(Trait))
          registerSuperCall(ctx.owner.enclosingClass.asClass, tree.symbol.owner.asClass)
      case _ =>
    }
    tree
  }

  private def registerSuperCall(sym: ClassSymbol, calls: ClassSymbol)(using Context) = {
    genBCodePhase match {
      case genBCodePhase: GenBCode =>
        genBCodePhase.registerSuperCall(sym, calls)
      case _ =>
    }
  }
}
