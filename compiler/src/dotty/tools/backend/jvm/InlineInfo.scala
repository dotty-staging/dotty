package dotty.tools.backend.jvm

import dotty.tools.backend.jvm.BTypes.InternalName
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.ClassSymbol
import org.objectweb.asm.tree.{ClassNode, ModuleNode}

/**
 * Metadata about a ClassBType, used by the inliner.
 *
 * More information may be added in the future to enable more elaborate inline heuristics.
 * Note that this class should contain information that can only be obtained from the ClassSymbol.
 * Information that can be computed from the ClassNode should be added to the call graph instead.
 *
 * @param isEffectivelyFinal True if the class cannot have subclasses: final classes, module
 *                           classes.
 * @param sam                If this class is a SAM type, the SAM's "\$name\$descriptor".
 * @param methodInfos        The [[MethodInlineInfo]]s for the methods declared in this class.
 *                           The map is indexed by the string s"\$name\$descriptor" (to
 *                           disambiguate overloads).
 * @param missingClassForWarning Contains a class to be used for a warning if an error occurred
 *                               when building this InlineInfo, if some classfile could not be found on
 *                               the classpath. This warning can be reported later by the inliner.
 * @param isAccessible       Whether this class's internals can be inlined into callsites, i.e.,
 *                           it is exported from a public module.
 */
final case class InlineInfo(isEffectivelyFinal: Boolean,
                            sam: Option[String],
                            methodInfos: collection.SortedMap[(String, String), MethodInlineInfo],
                            missingClassForWarning: Option[InternalName],
                            isAccessible: Boolean)

object InlineInfo {
  val empty = InlineInfo(isEffectivelyFinal = false, sam = None, methodInfos = collection.SortedMap.empty, missingClassForWarning = None, isAccessible = false)
}

/**
 * Metadata about a method, used by the inliner.
 *
 * @param effectivelyFinal  True if the method cannot be overridden (in Scala)
 * @param annotatedInline   True if the method is annotated `@inline`
 * @param annotatedNoInline True if the method is annotated `@noinline`
 */
final case class MethodInlineInfo(effectivelyFinal: Boolean = false,
                                  annotatedInline: Boolean = false,
                                  annotatedNoInline: Boolean = false)

trait InlineInfoLoader {
  def loadFromName(name: InternalName): InlineInfo
  def loadFromSymbol(classSym: ClassSymbol, internalName: InternalName)(using Context): InlineInfo
  def loadFromClassfile(classNode: ClassNode, moduleNode: Option[ModuleNode]): InlineInfo
}
