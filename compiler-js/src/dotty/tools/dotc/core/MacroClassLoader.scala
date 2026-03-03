package dotty.tools.dotc.core

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.util.Property

/** Stub MacroClassLoader for Scala.js - macros are not supported */
object MacroClassLoader {
  private val MacroClassLoaderKey = new Property.Key[ClassLoader]

  def fromContext(using Context): ClassLoader =
    ctx.property(MacroClassLoaderKey).getOrElse(getClass.getClassLoader)

  def init(ctx: FreshContext): ctx.type =
    ctx.setProperty(MacroClassLoaderKey, getClass.getClassLoader)
}
