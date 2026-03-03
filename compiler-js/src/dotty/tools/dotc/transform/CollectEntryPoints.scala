package dotty.tools.dotc
package transform

import core.*
import ast.tpd
import MegaPhase.*
import Contexts.*
import Symbols.*
import Phases.*
import dotty.tools.io.JarArchive
import dotty.tools.backend.jvm.GenBCode

/** Stub CollectEntryPoints for Scala.js */
class CollectEntryPoints extends MiniPhase:

  override def phaseName: String = CollectEntryPoints.name

  override def description: String = CollectEntryPoints.description

  override def isRunnable(using Context): Boolean =
    def forceRun = ctx.settings.XmainClass.isDefault && ctx.settings.outputDir.value.isInstanceOf[JarArchive]
    super.isRunnable && forceRun

  override def transformTypeDef(tree: tpd.TypeDef)(using Context): tpd.Tree =
    tree // No-op on JS: no JVM entry points

object CollectEntryPoints:
  val name: String = "Collect entry points"
  val description: String = "collect all entry points and save them in the context"
