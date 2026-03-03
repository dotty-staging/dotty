package dotty.tools.dotc.core

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.util.Property

/** Stub MacroClassLoader for Scala.js - macros are not supported */
object MacroClassLoader {
  private val MacroClassLoaderKey = new Property.Key[ClassLoader]

  import scala.language.unsafeNulls
  /** On Scala.js there's no classloader; return a dummy null */
  private val dummyClassLoader: ClassLoader = null

  def fromContext(using Context): ClassLoader =
    ctx.property(MacroClassLoaderKey).getOrElse(dummyClassLoader)

  def init(ctx: FreshContext): ctx.type =
    ctx.setProperty(MacroClassLoaderKey, dummyClassLoader)
}
