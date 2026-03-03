package dotty.tools.dotc
package transform

import java.io.{PrintWriter, StringWriter}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.FlatName
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Denotations.staticRef
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Constants.Constant

import dotty.tools.dotc.quoted.Interpreter

import scala.util.control.NonFatal
import dotty.tools.dotc.util.SrcPos
import dotty.tools.io.AbstractFileClassLoader

import scala.reflect.ClassTag

import dotty.tools.dotc.quoted.{PickledQuotes, QuoteUtils}

import scala.quoted.Quotes
import scala.quoted.runtime.impl.*
import dotty.tools.dotc.core.NameKinds

/** Utility class to splice quoted expressions - stub for Scala.js */
object Splicer {
  import tpd.*
  import Interpreter.*

  def splice(tree: Tree, splicePos: SrcPos, spliceExpansionPos: SrcPos, classLoader: ClassLoader)(using Context): Tree = tree match {
    case Quote(quotedTree, Nil) => quotedTree
    case _ =>
      report.error(em"Macro splicing is not supported on Scala.js", splicePos)
      ref(defn.Predef_undefined).withType(ErrorType(em"Macro splicing is not supported on Scala.js"))
  }

  def checkEscapedVariables(tree: Tree, expansionOwner: Symbol)(using Context): tree.type = tree

  def checkValidMacroBody(tree: Tree)(using Context): Unit = tree match {
    case Quote(_, Nil) => // ok
    case _ =>
      type Env = Set[Symbol]

      def checkValidStat(tree: Tree)(using Env): Env = tree match {
        case tree: ValDef if tree.symbol.is(Synthetic) =>
          checkIfValidArgument(tree.rhs)
          summon[Env] + tree.symbol
        case _ =>
          report.error("Macro should not have statements", tree.srcPos)
          summon[Env]
      }

      def checkIfValidArgument(tree: Tree)(using Env): Unit = tree match {
        case Block(Nil, expr) => checkIfValidArgument(expr)
        case Typed(expr, _) => checkIfValidArgument(expr)

        case Apply(Select(Quote(body, _), nme.apply), _) =>
          val noSpliceChecker = new TreeTraverser {
            def traverse(tree: Tree)(using Context): Unit = tree match
              case Splice(_) =>
                report.error("Quoted argument of macros may not have splices", tree.srcPos)
              case _ =>
                traverseChildren(tree)
          }
          noSpliceChecker.traverse(body)

        case Apply(TypeApply(fn, List(quoted)), _) if fn.symbol == defn.QuotedTypeModule_of =>
          // OK

        case Literal(Constant(value)) =>
          // OK

        case NamedArg(_, arg) =>
          checkIfValidArgument(arg)

        case SeqLiteral(elems, _) =>
          elems.foreach(checkIfValidArgument)

        case tree: Ident if summon[Env].contains(tree.symbol) || tree.symbol.is(Inline, butNot = Method) =>
          // OK

        case _ =>
          val extra = if tree.span.isZeroExtent then ": " + tree.show else ""
          report.error(
            s"""Malformed macro parameter$extra
              |
              |Parameters may only be:
              | * Quoted parameters or fields
              | * Literal values of primitive types
              | * References to `inline val`s
              |""".stripMargin, tree.srcPos)
      }

      def checkIfValidStaticCall(tree: Tree)(using Env): Unit = tree match {
        case closureDef(ddef @ DefDef(_, ValDefs(ev :: Nil) :: Nil, _, _)) if ddef.symbol.info.isContextualMethod =>
          checkIfValidStaticCall(ddef.rhs)(using summon[Env] + ev.symbol)

        case Block(stats, expr) =>
          val newEnv = stats.foldLeft(summon[Env])((env, stat) => checkValidStat(stat)(using env))
          checkIfValidStaticCall(expr)(using newEnv)

        case Typed(expr, _) =>
          checkIfValidStaticCall(expr)

        case Apply(Select(Quote(quoted, Nil), nme.apply), _) =>
          // OK, canceled and warning emitted

        case Call(fn, args)
            if (fn.symbol.isConstructor && fn.symbol.owner.owner.is(Package)) ||
               fn.symbol.is(Module) || fn.symbol.isStatic ||
               (fn.qualifier.symbol.is(Module) && fn.qualifier.symbol.isStatic) =>
          if (fn.symbol.flags.is(Inline))
            report.error("Macro cannot be implemented with an `inline` method", fn.srcPos)
          args.flatten.foreach(checkIfValidArgument)

        case Call(fn, args) if fn.symbol.name.is(NameKinds.InlineAccessorName) =>
          report.error(
            i"""Macro implementation is not statically accessible.
              |
              |Non-static inline accessor was generated in ${fn.symbol.owner}
              |""".stripMargin, tree.srcPos)
        case _ =>
          report.error(
            """Malformed macro.
              |
              |Expected the splice ${...} to contain a single call to a static method.
              |""".stripMargin, tree.srcPos)
      }

      checkIfValidStaticCall(tree)(using Set.empty)
  }

  def isMacroOwner(sym: Symbol)(using Context): Boolean =
    sym.is(Macro, butNot = Method) && sym.name == nme.MACROkw

  def inMacroExpansion(using Context) =
    ctx.owner.ownersIterator.exists(isMacroOwner)
}
