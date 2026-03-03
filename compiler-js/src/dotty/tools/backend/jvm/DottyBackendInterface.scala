package dotty.tools.backend.jvm

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Flags.*
import scala.reflect.ClassTag
import dotty.tools.io.AbstractFile
import dotty.tools.dotc.core.*
import Contexts.*
import Types.*
import Symbols.*
import Phases.*
import Decorators.em

import dotty.tools.dotc.util.ReadOnlyMap
import dotty.tools.dotc.report

import tpd.*

import StdNames.nme
import NameKinds.{LazyBitMapName, LazyLocalName}
import Names.Name

object DottyBackendInterface {

  private def erasureString(clazz: Class[?]): String = {
    if (clazz.isArray) "Array[" + erasureString(clazz.getComponentType) + "]"
    else clazz.getName
  }

  def requiredClass(str: String)(using Context): ClassSymbol =
    Symbols.requiredClass(str)

  def requiredClass[T](using evidence: ClassTag[T], ctx: Context): Symbol =
    requiredClass(erasureString(evidence.runtimeClass))

  def requiredModule(str: String)(using Context): Symbol =
    Symbols.requiredModule(str)

  def requiredModule[T](using evidence: ClassTag[T], ctx: Context): Symbol = {
    val moduleName = erasureString(evidence.runtimeClass)
    val className = if (moduleName.endsWith("$")) moduleName.dropRight(1) else moduleName
    requiredModule(className)
  }

  given symExtensions: AnyRef with
    extension (sym: Symbol)

      def isInterface(using Context): Boolean = sym.is(PureInterface) || sym.is(Trait)

      def isStaticConstructor(using Context): Boolean = (sym.isStaticMember && sym.isClassConstructor) || (sym.name eq nme.STATIC_CONSTRUCTOR)

      private def isStaticModuleField(using Context): Boolean =
        sym.owner.isStaticModuleClass && sym.isField && !sym.name.is(LazyBitMapName) && !sym.name.is(LazyLocalName)

      def isStaticMember(using Context): Boolean =
        (sym ne NoSymbol) &&
        (sym.is(JavaStatic) || sym.isScalaStatic || sym.isStaticModuleField)

      def isStaticModuleClass(using Context): Boolean =
        sym.is(Module) && {
          val original = toDenot(sym).initial
          val validity = original.validFor
          atPhase(validity.phaseId) {
            toDenot(sym).isStatic
          }
        }

      def originalLexicallyEnclosingClass(using Context): Symbol =
        if (sym.exists) {
          val validity = toDenot(sym).initial.validFor
          atPhase(validity.phaseId) {
            toDenot(sym).lexicallyEnclosingClass
          }
        } else NoSymbol

      def isTopLevelModuleClass(using Context): Boolean =
        sym.is(ModuleClass) &&
        atPhase(flattenPhase) {
          toDenot(sym).owner.is(PackageClass)
        }

      def javaSimpleName(using Context): String = toDenot(sym).name.mangledString
      def javaClassName(using Context): String = toDenot(sym).fullName.mangledString
      def javaBinaryName(using Context): String = javaClassName.replace('.', '/')

    end extension

  end symExtensions
}
