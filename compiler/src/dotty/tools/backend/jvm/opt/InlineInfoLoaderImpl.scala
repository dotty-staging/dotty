package dotty.tools.backend.jvm.opt

import dotty.tools.backend.*
import dotty.tools.backend.jvm.*
import dotty.tools.backend.jvm.BTypes.*
import dotty.tools.backend.jvm.SymbolUtils.given
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.toTermName
import dotty.tools.dotc.core.Definitions.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Phases.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.abstractTermNameFilter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.{ClassNode, ModuleNode}

import scala.collection.mutable
import scala.jdk.CollectionConverters.ListHasAsScala

final class InlineInfoLoaderImpl(byteCodeRepository: BCodeRepository, primitives: ScalaPrimitives) extends InlineInfoLoader {

  def loadFromName(name: InternalName): InlineInfo =
    if OptimizerUtils.isSCoverage(name) then InlineInfo.empty
    else byteCodeRepository.classNode(name) match {
      case Right(classNode, moduleNode) =>
        loadFromClassfile(classNode, moduleNode)
      case Left(missingClass) =>
        InlineInfo.empty.copy(missingClassForWarning = Some(missingClass.internalName))
    }


  def loadFromSymbol(classSym: ClassSymbol, internalName: InternalName, bTypeLoader: BTypeLoader)(using Context): InlineInfo = {
    // phase travel required (or at least it was in Scala 2). for nested classes, it checks if the
    // enclosingTopLevelClass is being compiled. after flatten, all classes are considered top-level,
    // so it would return `false`.
    if atPhase(picklerPhase.next) (classSym.isDefinedInCurrentRun)
    then buildInlineInfoFromClassSymbol(classSym, bTypeLoader)
    else loadFromName(internalName)
  }

  private def buildInlineInfoFromClassSymbol(classSym: ClassSymbol, bTypeLoader: BTypeLoader)(using Context): InlineInfo = {
    // We only want an approximation of SAMs for inlining heuristics, no need to check FunctionalInterface annotations or such
    val abstractMembers = classSym.memberNames(abstractTermNameFilter).iterator.map(classSym.classInfo.member).map(_.symbol).filter(_.is(Method)).toList
    val sam = abstractMembers match
      case List(single) =>
        val btype = bTypeLoader.methodBTypeFromSymbol(single)
        Some(single.javaSimpleName + btype.descriptor)
      case _ => None

    // Phantom symbols (e.g. constructor proxy `apply`s) have `NoType` info after erasure and are normally
    // dropped from the decls when their owner's unit is erased. A class from a unit that was suspended
    // in this run (e.g. on a macro defined in the same run) hasn't been through erasure, so skip them here.
    def keepMember(sym: Symbol) = sym.is(Method) && !sym.is(PhantomSymbol) && !primitives.isPrimitive(sym)

    val classMethods = classSym.info.decls.iterator.filter(keepMember)
    val methods = if classSym.is(JavaDefined) then
      // Phase travel important for nested classes (scala-dev#402). When a java class symbol A$B
      // is compiled from source, this ensures that `companionModule` doesn't return the `A$B`
      // symbol created for the `A$B.class` file on the classpath, which might be different.
      val companion = atPhase(picklerPhase.next) {
        classSym.companionModule
      }
      val staticMethods = companion.info.decls.iterator.filter(m => !m.isConstructor && keepMember(m))
      staticMethods ++ classMethods
    else
      val staticForwarders = if classSym.is(Trait) then
        // !!! This logic duplicates PlainSkelBuilder::makeStaticForwarder, copy changes there !!!
        classSym.info.decls.filter(s => s.isTerm && !s.is(Private) && !s.isStaticMember && s.name != nme.TRAIT_CONSTRUCTOR).map(s => {
          SymbolUtils.makeStatifiedDefSymbol(s.asTerm, SymbolUtils.traitSuperAccessorName(s).toTermName)
        })
      else Nil
      classMethods ++ staticForwarders

    // Primitive methods cannot be inlined, so there's no point in building a MethodInlineInfo. Also, some
    // primitive methods (e.g., `isInstanceOf`) have non-erased types, which confuses [[typeToBType]].
    val methodInlineInfos = new collection.mutable.TreeMap[(String, String), MethodInlineInfo]()
    methods.foreach {
      methodSym =>
        val name = methodSym.javaSimpleName // same as in genDefDef
        val signature = (name, bTypeLoader.methodBTypeFromSymbol(methodSym).descriptor)

        // In a trait, accesses to "modules" like enums are translated by the frontend as final methods,
        // even though they are logically not final since classes implementing the trait will also have that method,
        // so we must explicitly consider them to be non-final.
        // TODO: This feels like something fundamentally weird in trees that should not exist.
        val info = MethodInlineInfo(
          effectivelyFinal = methodSym.isEffectivelyFinal && !methodSym.is(ModuleVal),
          annotatedInline = methodSym.hasAnnotation(defn.InlineAnnot),
          annotatedNoInline = methodSym.hasAnnotation(defn.NoInlineAnnot))

        methodInlineInfos(signature) = info
    }

    // if we have a symbol, we're compiling the class, so we assume it's accessible
    InlineInfo(classSym.is(Final), sam, methodInlineInfos, None, isAccessible = true)
  }

  /**
   * Build the InlineInfo using the metadata available in the classfile (ACC_FINAL flags, etc.).
   */
  def loadFromClassfile(classNode: ClassNode, moduleNode: Option[ModuleNode]): InlineInfo = {
    // when building MethodInlineInfos for the members of a ClassSymbol, we exclude those methods
    // in scalaPrimitives. This is necessary because some of them have non-erased types, which would
    // require special handling. Excluding is OK because they are never inlined.
    // Here we are parsing from a classfile and we don't need to do anything special. Many of these
    // primitives don't even exist, for example Any.isInstanceOf.
    val methodInfos = new mutable.TreeMap[(String, String), MethodInlineInfo]()
    val isFinalClass = BCodeUtils.isFinalClass(classNode)
    classNode.methods.forEach(methodNode => {
      val info = MethodInlineInfo(effectivelyFinal = isFinalClass || BCodeUtils.isFinalMethod(methodNode))
      methodInfos((methodNode.name, methodNode.desc)) = info
    })

    // We only want an approximation of SAMs for inlining heuristics, no need to check FunctionalInterface annotations or such
    val abstractMethods = classNode.methods.asScala.filter(m => (m.access & Opcodes.ACC_ABSTRACT) != 0)
    val sam = if abstractMethods.size == 1 then Some(abstractMethods.head.name + abstractMethods.head.desc) else None

    // No module => always accessible
    val isAccessible = moduleNode.forall(m =>
      val nodePackageName = classNode.name.substring(0, classNode.name.lastIndexOf('/'))
      m.exports.asScala.exists(e => (e.modules == null || e.modules.size == 0) && e.packaze == nodePackageName)
    )

    InlineInfo(isFinalClass, sam, methodInfos, None, isAccessible)
  }
}
