package scala.tools.testkit

import scala.quoted.*
import scala.reflect.api
import scala.reflect.runtime.universe as ru

/** Scala 3 port: a test-only stand-in for the Scala 2 compiler's `TypeTag` materializer
 *  macro, so that vendored tests using `typeOf`/`symbolOf` can be compiled with Scala 3.
 *
 *  The macro serializes the (simple) static type to a small description and the tag
 *  reconstructs it against the requested mirror at runtime, like a compiler-generated
 *  `TypeCreator` would. Only the shapes used by the vendored test suite are supported:
 *  classes and traits, applied types, module singletons; wildcard arguments are
 *  approximated by `Any`.
 */
object TypeTagMaterializer:

  sealed trait TDesc
  final case class TClass(fullName: String, args: List[TDesc]) extends TDesc
  final case class TModule(fullName: String) extends TDesc

  given ToExpr[TDesc] with
    def apply(d: TDesc)(using Quotes): Expr[TDesc] = d match
      case TClass(fn, args) => '{ TClass(${Expr(fn)}, ${Expr(args)}) }
      case TModule(fn)      => '{ TModule(${Expr(fn)}) }

  def buildType(u: api.Universe)(m: api.Mirror[u.type], d: TDesc): u.Type = d match
    case TClass(fn, Nil)  => m.staticClass(fn).toType
    case TClass(fn, args) =>
      u.appliedType(m.staticClass(fn).toTypeConstructor, args.map(buildType(u)(m, _)))
    case TModule(fn) =>
      val mod = m.staticModule(fn)
      u.internal.singleType(u.NoPrefix, mod)

  def makeTag[T](d: TDesc): ru.TypeTag[T] =
    val mirror0 = ru.runtimeMirror(getClass.getClassLoader)
    ru.TypeTag[T](mirror0, new api.TypeCreator {
      // `U # Type` cannot be written in Scala 3; the dependent form conforms to it
      def apply[U <: api.Universe with Singleton](m: api.Mirror[U]): m.universe.Type =
        val u: m.universe.type = m.universe
        buildType(u)(m.asInstanceOf[api.Mirror[u.type]], d)
    })

  inline given materialize[T]: ru.TypeTag[T] = ${ impl[T] }

  private def impl[T: Type](using Quotes): Expr[ru.TypeTag[T]] =
    import quotes.reflect.*
    def describe(tp: TypeRepr): TDesc = tp.dealias.widen match
      case AppliedType(tycon, args) =>
        TClass(fullNameOf(tycon.typeSymbol), args.map {
          case arg if arg.typeSymbol.exists && !isWildcard(arg) => describe(arg)
          case _ => TClass("scala.Any", Nil)
        })
      case tr if tr.termSymbol.exists && tr.termSymbol.flags.is(Flags.Module) =>
        TModule(fullNameOf(tr.termSymbol).stripSuffix("$"))
      case tr if tr.typeSymbol.exists && tr.typeSymbol.flags.is(Flags.Module) =>
        TModule(fullNameOf(tr.typeSymbol).stripSuffix("$"))
      case tr if tr.typeSymbol.isClassDef =>
        TClass(fullNameOf(tr.typeSymbol), Nil)
      case other =>
        report.errorAndAbort(s"unsupported type shape for test TypeTag materializer: ${other.show}")
    def isWildcard(tp: TypeRepr): Boolean = tp match
      case _: TypeBounds => true
      case _ => false
    def fullNameOf(sym: Symbol): String = sym.fullName
    val repr = TypeRepr.of[T] match
      case tr @ TermRef(_, _) => tr // keep singletons
      case tr => tr
    val desc = repr match
      case tr @ TermRef(_, _) if tr.termSymbol.flags.is(Flags.Module) =>
        TModule(tr.termSymbol.fullName.stripSuffix("$"))
      case tr => describe(tr)
    '{ makeTag[T](${Expr(desc)}) }
