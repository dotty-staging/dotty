package dotty.tools.dotc
package quoted

import scala.collection.mutable
import scala.reflect.ClassTag

import java.io.{PrintWriter, StringWriter}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.TreeMapWithImplicits
import dotty.tools.dotc.core.Annotations.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Denotations.staticRef
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.FlatName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.quoted.*
import dotty.tools.dotc.typer.ImportInfo.withRootImports
import dotty.tools.dotc.util.SrcPos
import dotty.tools.dotc.reporting.Message
import dotty.tools.io.AbstractFileClassLoader
import dotty.tools.dotc.core.CyclicReference

/** Tree interpreter for metaprogramming constructs - stub for Scala.js */
class Interpreter(pos: SrcPos, classLoader0: ClassLoader)(using Context):
  import Interpreter.*
  import tpd.*

  val classLoader = classLoader0

  type Env = Map[Symbol, Object]
  def emptyEnv: Env = Map.empty
  inline def env(using e: Env): e.type = e

  final def interpret[T](tree: Tree)(using ct: ClassTag[T]): Option[T] =
    throw new StopInterpretation(em"Macro interpretation is not supported on Scala.js", pos)

  protected def interpretTree(tree: Tree)(using Env): Object =
    throw new StopInterpretation(em"Macro interpretation is not supported on Scala.js", pos)

end Interpreter

object Interpreter:
  class StopInterpretation(val msg: Message, val pos: SrcPos) extends Exception

  object Call:
    import tpd.*
    def unapply(arg: Tree)(using Context): Option[(RefTree, List[List[Tree]])] =
      Call0.unapply(arg).map((fn, args) => (fn, args.reverse))

    private object Call0 {
      def unapply(arg: Tree)(using Context): Option[(RefTree, List[List[Tree]])] = arg match {
        case Select(Call0(fn, args), nme.apply) if defn.isContextFunctionType(fn.tpe.widenDealias.finalResultType) =>
          Some((fn, args))
        case fn: Ident => Some((tpd.desugarIdent(fn).withSpan(fn.span), Nil))
        case fn: Select => Some((fn, Nil))
        case Apply(f @ Call0(fn, argss), args) => Some((fn, args :: argss))
        case TypeApply(Call0(fn, argss), _) => Some((fn, argss))
        case _ => None
      }
    }
  end Call

  enum ClassOrigin:
    case Classpath, Source

  object MissingClassValidInCurrentRun {
    def unapply(targetException: Throwable)(using Context): Option[(Symbol, ClassOrigin)] = None
  }

  def suspendOnMissing(sym: Symbol, origin: ClassOrigin, pos: SrcPos)(using Context): Nothing =
    throw StopInterpretation(em"Macro interpretation is not supported on Scala.js: missing ${sym.showLocated}", pos)
