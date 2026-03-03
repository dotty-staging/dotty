package dotty.tools.dotc
package transform

import core.Contexts.Context
import core.DenotTransformers.IdentityDenotTransformer
import core.Flags.*

/** Stub InstrumentCoverage for Scala.js — coverage instrumentation not supported. */
class InstrumentCoverage extends MacroTransform with IdentityDenotTransformer:
  override def phaseName = InstrumentCoverage.name
  override def description = InstrumentCoverage.description
  override def isEnabled(using ctx: Context) = false
  override protected def newTransformer(using Context) = new Transformer:
    override def transform(tree: ast.tpd.Tree)(using Context) = tree

object InstrumentCoverage:
  val name: String = "instrumentCoverage"
  val description: String = "instrument code for coverage checking"
