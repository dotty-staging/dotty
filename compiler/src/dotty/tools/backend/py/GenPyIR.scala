package dotty.tools.backend.py

import dotty.tools.dotc.*, core.*
import Contexts.*
import Phases.*
import Decorators.*
import ast.tpd, ast.Trees, Trees.*

class GenPyIR extends Phase:
  override def phaseName: String = GenPyIR.name
  override def description: String = GenPyIR.description
  override def isEnabled(using Context): Boolean = ctx.settings.YpyGen.value
  override def isRunnable(using Context): Boolean = true

  def run(using Context): Unit =
    val codegen = new PyCodeGen
    codegen.genCompilationUnit(ctx.compilationUnit)

object GenPyIR:
  val name: String = "genPyIR"
  val description: String = "generate Python IR"
