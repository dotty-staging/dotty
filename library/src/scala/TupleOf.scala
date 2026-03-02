package scala

import scala.annotation.publicInBinary
import scala.quoted.*

object TupleOf {
  opaque type AnyTupleOf[A] = Tuple
  opaque type TupleOf[A, I <: Int] <: AnyTupleOf[A] = Tuple //.Fill[A, I] // only relevant for conversions

  extension [A](any: AnyTupleOf[A])
    def iterator: Iterator[A] = any.productIterator.asInstanceOf[Iterator[A]]
    def toIArray(using reflect.ClassTag[A]): IArray[A] =
      val limit = any.productArity
      val arr = new Array[A](limit)
      var i = 0
      while i < limit do
        arr(i) = any.productElement(i).asInstanceOf[A]
        i += 1
      IArray.unsafeFromArray(arr)
    def toTupleAny: Tuple = any

  extension [A, I <: Int](tup: TupleOf[A, I])
    def toTuple: Tuple.Fill[A, I] =
      // evidence should be proven at construction
      tup.asInstanceOf[Tuple.Fill[A, I]]

  // for demonstration, remove later
  inline def namesOf[N <: Tuple, T <: Tuple](nt: NamedTuple.NamedTuple[N, T]): TupleOf[String, Tuple.Size[N]] =
    constValueOfTuple[N, String]
  // for demonstration, remove later
  inline def namesOfAny[N <: Tuple, T <: Tuple](nt: NamedTuple.NamedTuple[N, T]): AnyTupleOf[String] =
    constValueOfTupleAny[N, String]

  inline def constValueOfTuple[T <: Tuple, A]: TupleOf[A, Tuple.Size[T]] =
    ${ constValueOfTupleImpl[T, A, Tuple.Size[T]] }

  inline def constValueOfTupleAny[T <: Tuple, A]: AnyTupleOf[A] =
    ${ constValueOfTupleAnyImpl[T, A] }

  inline def constValueOfTupleAnyInline[T <: Tuple, A]: AnyTupleOf[A] =
    // TODO: compare performance with constValueOfTupleAny
    inline compiletime.erasedValue[Tuple.Union[T]] match
      case _: A => buildAny[A](compiletime.constValueTuple[T])

  inline def constValueOfTupleInline[T <: Tuple, A]: TupleOf[A, Tuple.Size[T]] =
    // TODO: compare performance with constValueOfTuple
    inline compiletime.erasedValue[T] match
      case _: Tuple.Fill[A, Tuple.Size[T]] => build[A, Tuple.Size[T]](compiletime.constValueTuple[T])

  inline def fromTuple(tuple: Tuple)[T >: tuple.type <: Tuple]: TupleOf[Tuple.Union[T], Tuple.Size[T]] =
    build[Tuple.Union[T], Tuple.Size[T]](tuple)

  inline def fromTupleOf[A](tuple: Tuple)[T >: tuple.type <: Tuple]: TupleOf[A, Tuple.Size[T]] =
    inline compiletime.erasedValue[T] match
      case _: Tuple.Fill[A, Tuple.Size[T]] => build[A, Tuple.Size[T]](tuple)

  inline def checked[A, I <: Int](tuple: Tuple)[T >: tuple.type <: Tuple]: TupleOf[A, I] =
    // TODO: compare performance with checkShape macro version,
    // ofc this is limited by match type recursion while checkShape is not
    inline compiletime.erasedValue[T] match
      case _: Tuple.Fill[A, I] => build[A, I](tuple)

  inline def checkShape[A, I <: Int]()[T <: Tuple](inline tuple: T): TupleOf[A, I] = ${ checkShapeImpl[T, A, I]('tuple) }

  private def constValueOfTupleImpl[T <: Tuple: Type, A: Type, I <: Int: Type](using Quotes): Expr[TupleOf[A, I]] = {
    import quotes.reflect.*

    val constI = TypeRepr.of[I] match
      case ConstantType(IntConstant(i)) => i
      case _ => report.errorAndAbort(s"type I: ${Type.show[I]} is not a constant Int")

    val Atype = TypeRepr.of[A]
    val PairRef = Symbol.requiredClass("scala.*:").typeRef
    val EmptyRef = Symbol.requiredModule("scala.EmptyTuple").termRef

    def success(tuple: Expr[Tuple]) = '{ build[A, I]($tuple) }
    def fail = report.errorAndAbort(s"${Type.show[T]} does not match shape required by TupleOf[${Type.show[A]}, ${Type.show[I]}]")

    def loopTup(tp: TypeRepr, i: Int, buf: collection.mutable.Builder[Expr[A], Vector[Expr[A]]]): Expr[TupleOf[A, I]] = tp match
      case AppliedType(PairRef, (a @ ConstantType(const)) :: as :: Nil) if a <:< Atype =>
        loopTup(as, i + 1, buf += Literal(const).asExprOf[A])
      case EmptyRef if i == constI =>
        success(Expr.ofTupleFromSeq(buf.result()))
      case _ =>
        fail

    def entry(tp: TypeRepr): Expr[TupleOf[A, I]] = tp match
      case AppliedType(PairRef, a :: as :: Nil) if a <:< Atype => loopTup(as, 1, Vector.newBuilder[Expr[A]])
      case AppliedType(ref, ts) if defn.isTupleClass(ref.typeSymbol) =>
        val collapsed = ts.foldLeft(Vector.newBuilder[Expr[A]])((as, tpe) => tpe match {
          case a @ ConstantType(const) if a <:< Atype => as += Literal(const).asExprOf[A]
          case _ => as
        }).result()
        if collapsed.size == constI then
          success(Expr.ofTupleFromSeq(collapsed))
        else
          fail
      case _ =>
        fail

    entry(TypeRepr.of[T])
  }

  private def constValueOfTupleAnyImpl[T <: Tuple: Type, A: Type](using Quotes): Expr[AnyTupleOf[A]] = {
    import quotes.reflect.*

    val Atype = TypeRepr.of[A]
    val PairRef = Symbol.requiredClass("scala.*:").typeRef
    val EmptyRef = Symbol.requiredModule("scala.EmptyTuple").termRef

    def success(tuple: Expr[Tuple]) = '{ buildAny[A]($tuple) }
    def fail = report.errorAndAbort(s"${Type.show[T]} does not match shape required by AnyTupleOf[${Type.show[A]}]")

    def loopTup(tp: TypeRepr, buf: collection.mutable.Builder[Expr[A], Vector[Expr[A]]]): Expr[AnyTupleOf[A]] = tp match
      case AppliedType(PairRef, (a @ ConstantType(const)) :: as :: Nil) if a <:< Atype =>
        loopTup(as, buf += Literal(const).asExprOf[A])
      case EmptyRef =>
        success(Expr.ofTupleFromSeq(buf.result()))
      case _ =>
        fail

    def entry(tp: TypeRepr): Expr[AnyTupleOf[A]] = tp match
      case AppliedType(PairRef, a :: as :: Nil) if a <:< Atype => loopTup(as, Vector.newBuilder[Expr[A]])
      case AppliedType(ref, ts) if defn.isTupleClass(ref.typeSymbol) =>
        var succeed = true
        val collapsed = ts.foldLeft(Vector.newBuilder[Expr[A]])((as, tpe) => tpe match {
          case a @ ConstantType(const) if a <:< Atype => as += Literal(const).asExprOf[A]
          case _ =>
            succeed = false
            as
        }).result()
        if succeed then
          success(Expr.ofTupleFromSeq(collapsed))
        else
          fail
      case _ =>
        fail

    entry(TypeRepr.of[T])
  }

  private def checkShapeImpl[T <: Tuple: Type, A: Type, I <: Int: Type](tuple: Expr[T])(using Quotes): Expr[TupleOf[A, I]] = {
    import quotes.reflect.*

    val constI = TypeRepr.of[I] match
      case ConstantType(IntConstant(i)) => i
      case _ => report.errorAndAbort(s"type I: ${Type.show[I]} is not a constant Int")

    val Atype = TypeRepr.of[A]
    val PairRef = Symbol.requiredClass("scala.*:").typeRef
    val EmptyRef = Symbol.requiredModule("scala.EmptyTuple").termRef

    def success = '{ build[A, I]($tuple) }
    def fail = report.errorAndAbort(s"${tuple.show} does not match shape required by TupleOf[${Type.show[A]}, ${Type.show[I]}]")

    def loopTup(tp: TypeRepr, i: Int): Expr[TupleOf[A, I]] = tp match
      case AppliedType(PairRef, a :: as :: Nil) if a <:< Atype => loopTup(as, i + 1)
      case EmptyRef if i == constI =>
        success
      case _ =>
        fail

    def entry(tp: TypeRepr): Expr[TupleOf[A, I]] = tp match
      case AppliedType(PairRef, a :: as :: Nil) if a <:< Atype => loopTup(as, 1)
      case AppliedType(ref, ts) if defn.isTupleClass(ref.typeSymbol) && ts.size == constI && ts.forall(_ <:< Atype) =>
        success
      case _ =>
        fail

    entry(TypeRepr.of[T])
  }

  @publicInBinary
  private[TupleOf] inline def build[A, I <: Int](tuple: Tuple): TupleOf[A, I] = tuple.asInstanceOf[TupleOf[A, I]]
  private[TupleOf] inline def buildAny[A](tuple: Tuple): AnyTupleOf[A] = tuple.asInstanceOf[AnyTupleOf[A]]
}
