/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package dotty.tools.backend.jvm.opt

import dotty.tools.backend.jvm.BTypes.InternalName
import dotty.tools.backend.jvm.opt.{BCodeRepository, ClassNotFound, NoClassBTypeInfo, OptimizerWarning}
import dotty.tools.backend.jvm.*
import dotty.tools.dotc.core.StdNames.nme

import scala.annotation.{switch, unused}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.{ClassNode, InnerClassNode, ModuleNode}

class BTypesFromClassfile(byteCodeRepository: BCodeRepository, cache: ClassBType.Cache, inlineInfoLoader: InlineInfoLoader) {

  /**
   * Obtain the BType for a type descriptor or internal name. For class descriptors, the ClassBType
   * is constructed by parsing the corresponding classfile.
   *
   * Some JVM operations use either a full descriptor or only an internal name. Example:
   *   ANEWARRAY java/lang/String    // a new array of strings (internal name for the String class)
   *   ANEWARRAY [Ljava/lang/String; // a new array of arrays of string (full descriptor for the String class)
   *
   * This method supports both descriptors and internal names.
   */
  def bTypeForDescriptorOrInternalNameFromClassfile(descOrIntN: String): Either[OptimizerWarning, BType] = (descOrIntN(0): @switch) match {
    case '['                           => bTypeForDescriptorFromClassfile(descOrIntN.substring(1)).map(ArrayBType.apply)
    case 'L' if descOrIntN.last == ';' => bTypeForDescriptorFromClassfile(descOrIntN)
    case _                             => classBTypeFromParsedClassfile(descOrIntN)
  }

  def bTypeForDescriptorFromClassfile(desc: String): Either[OptimizerWarning, BType] = (desc(0): @switch) match {
    case 'V' => Right(UNIT)
    case 'Z' => Right(BOOL)
    case 'C' => Right(CHAR)
    case 'B' => Right(BYTE)
    case 'S' => Right(SHORT)
    case 'I' => Right(INT)
    case 'F' => Right(FLOAT)
    case 'J' => Right(LONG)
    case 'D' => Right(DOUBLE)
    case '[' => bTypeForDescriptorFromClassfile(desc.substring(1)).map(ArrayBType.apply)
    case 'L' => if desc.last == ';' then classBTypeFromParsedClassfile(desc.substring(1, desc.length - 1))
                else throw new IllegalArgumentException(s"Invalid class-like descriptor: $desc")
    case _   => throw new IllegalArgumentException(s"Not a descriptor: $desc")
  }

  /**
   * Parse the classfile for `internalName` and construct the BTypes.ClassBType. If the classfile cannot
   * be found in the `byteCodeRepository`, the `info` of the resulting ClassBType is undefined.
   */
  def classBTypeFromParsedClassfile(internalName: InternalName): Either[OptimizerWarning, ClassBType] = {
    cache(internalName) { _ =>
      byteCodeRepository.classNode(internalName) match {
        case Left(msg) => Left(NoClassBTypeInfo(msg))
        case Right(c, m) => computeClassInfoFromClassNode(c, m)
      }
    }
  }

  /**
   * Construct the BTypes.ClassBType for a parsed classfile.
   */
  def classBTypeFromClassNode(classNode: ClassNode, moduleNode: Option[ModuleNode]): Either[OptimizerWarning, ClassBType] = {
    cache(classNode.name) { _ =>
      computeClassInfoFromClassNode(classNode, moduleNode)
    }
  }

  private def computeClassInfoFromClassNode(classNode: ClassNode, moduleNode: Option[ModuleNode]): Either[OptimizerWarning, ClassInfo] = {
    val superClass = classNode.superName match {
      case null =>
        assert(classNode.name == ClassBType.javaLangObjectInternalName, s"class with missing super type: ${classNode.name}")
        Right(None)
      case superName =>
        classBTypeFromParsedClassfile(superName).map(Some.apply)
    }

    val flags = classNode.access

    /*
     * Find all nested classes of classNode. The innerClasses attribute contains all nested classes
     * that are declared inside classNode or used in the bytecode of classNode. So some of them are
     * nested in some other class than classNode, and we need to filter them.
     *
     * For member classes, innerClassNode.outerName is defined, so we compare that to classNode.name.
     *
     * For local and anonymous classes, innerClassNode.outerName is null. Such classes are required
     * to have an EnclosingMethod attribute declaring the outer class. So we keep those local and
     * anonymous classes whose outerClass is classNode.name.
     */
    def nestedInCurrentClass(innerClassNode: InnerClassNode): Either[OptimizerWarning, Boolean] = {
      if innerClassNode.outerName != null && innerClassNode.outerName == classNode.name then
        Right(true)
      else if innerClassNode.outerName == null then
        byteCodeRepository.classNode(innerClassNode.name).map { case (classNodeForInnerClass, _) =>
          classNodeForInnerClass.outerClass == classNode.name
        }
      else
        Right(false)
    }
    
    def collect(it: Iterator[Either[OptimizerWarning, ClassBType]]): Either[OptimizerWarning, List[ClassBType]] =
      it.foldLeft(Right(Nil).asInstanceOf[Either[OptimizerWarning, List[ClassBType]]]){
        case (Right(xs), Right(x)) => Right(x :: xs)
        case (Left(l), _) => Left(l)
        case (_, Left(l)) => Left(l)
      }.map(_.reverse)

    def nestedClasses: Either[OptimizerWarning, List[ClassBType]] = 
      collect(classNode.innerClasses.asScala.iterator.collect(Function.unlift(i =>
        nestedInCurrentClass(i) match
          case Left(l) => Some(Left(l))
          case Right(false) => None
          case Right(true) => Some(classBTypeFromParsedClassfile(i.name))
      )))

    // if classNode is a nested class, it has an innerClass attribute for itself. in this
    // case we build the NestedInfo.
    def nestedInfo = classNode.innerClasses.asScala.find(_.name == classNode.name) match {
      case Some(innerEntry) =>
        val enclosingClass =
          if (innerEntry.outerName != null) {
            // if classNode is a member class, the outerName is non-null
            classBTypeFromParsedClassfile(innerEntry.outerName)
          } else {
            // for anonymous or local classes, the outerName is null, but the enclosing class is
            // stored in the EnclosingMethod attribute (which ASM encodes in classNode.outerClass).
            classBTypeFromParsedClassfile(classNode.outerClass)
          }
        val staticFlag = (innerEntry.access & Opcodes.ACC_STATIC) != 0
        enclosingClass.map(ec => Some(NestedInfo(ec, Option(innerEntry.outerName), Option(innerEntry.innerName), staticFlag)))
      case None =>
        Right(None)
    }

    val interfaces = collect(classNode.interfaces.asScala.iterator.map(classBTypeFromParsedClassfile))

    (superClass, interfaces, nestedClasses, nestedInfo) match
      case (Right(sc), Right(is), Right(ncs), Right(ni)) => Right(ClassInfo(sc, is, flags, ncs, ni, inlineInfoLoader.loadFromClassfile(classNode, moduleNode)))
      case (Left(l), _, _, _) => Left(l)
      case (_, Left(l), _, _) => Left(l)
      case (_, _, Left(l), _) => Left(l)
      case (_, _, _, Left(l)) => Left(l)
  }

}
