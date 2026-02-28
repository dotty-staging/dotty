package scala

import scala.annotation.publicInBinary
import scala.quoted.*

object TupleOf {
  opaque type AnyTupleOf[A] = Tuple
  opaque type TupleOf[A, I <: Int] <: AnyTupleOf[A] = Tuple.Fill[A, I]

  inline def fromTuple2[A](tuple: Tuple)[T >: tuple.type <: Tuple]: TupleOf[A, Tuple.Size[T]] =
    inline compiletime.erasedValue[Tuple.Map[T, [X] =>> A]] match
      case _: T => build[A, Tuple.Size[T]](tuple)

  inline def apply[A, I <: Int]()[T <: Tuple](tuple: T): TupleOf[A, I] = ${ applyImpl[T, A, I]('tuple) }

  private def applyImpl[T <: Tuple: Type, A: Type, I <: Int: Type](tuple: Expr[T])(using Quotes): Expr[TupleOf[A, I]] =
    import quotes.reflect.*

    val constI = TypeRepr.of[I] match
      case ConstantType(IntConstant(i)) => i
      case _ => report.errorAndAbort(s"type I: ${Type.show[I]} is not a constant Int")

    val Atype = TypeRepr.of[A]

    val xxlClass = Symbol.requiredClass("scala.runtime.TupleXXL")

    def isTupleApply(sym: Symbol): Boolean =
      sym.isDefDef
      && ((sym.name == "<init>" && defn.isTupleClass(sym.owner))
      || sym.name == "apply" && defn.isTupleClass(sym.owner.companionClass) || xxlClass == sym.owner)

    def loop(term: Term): Expr[TupleOf[A, I]] = term match
      case tapp @ TypeApply(Select(pre, ref), _) if ref == "$asInstanceOf" =>
        loop(pre)
      case app @ Apply(pre, args) if isTupleApply(pre.symbol) =>
        if args.length == constI && args.forall(_.tpe <:< Atype) then
          Apply(TypeApply(Ref(Symbol.requiredMethod("scala.TupleOf.build")), List(TypeTree.of[A], TypeTree.of[I])), List(app))
            .asExprOf[TupleOf[A, I]]
        else
          report.errorAndAbort("could not validate", tuple)

    loop(tuple.asTerm)

  @publicInBinary
  private[TupleOf] def build[A, I <: Int](tuple: Tuple): TupleOf[A, I] = tuple.asInstanceOf[TupleOf[A, I]]
}
