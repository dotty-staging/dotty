package dotty.tools.backend.py

import dotty.tools.dotc.*, core.*, Contexts.*, Types.*, Symbols.*
import Decorators.*
import ast.tpd, ast.Trees, Trees.*

class PyCodeGen(using genCtx: Context):
  def genCompilationUnit(unit: CompilationUnit): Unit =
    val tree = unit.tpdTree
    tree match
      case PackageDef(_, stats) =>
        println(i"Got a package def")
        stats.foreach: stat =>
          println(stat.toString)
          println(stat.show)
      case _ => assert(false, i"Unsupported tree: ${tree.show}")
