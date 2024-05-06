//> using options -language:experimental.modularity -source future

import scala.annotation.implicitNotFound
import scala.compiletime.ops.int.{-}
import scala.Tuple.Size

type IsTupleOf[-T, U] =
  T match
    case EmptyTuple => true
    case x *: xs =>
      x match
        case U => IsTupleOf[xs, U]
        case _ => false

@implicitNotFound("Expected a tuple of ${T}")
trait TupleOf[T]:
  type Self <: Tuple

given [T <: Tuple, U](using ev: IsTupleOf[T, U] =:= true) => (T is TupleOf[U])()

@implicitNotFound("Expected a tuple of length ${N}")
trait OfLength[-T <: Int : Singleton]:
  type Self <: Tuple

given [T <: Tuple, N <: Int : Singleton](using ev: Size[T] =:= N) => (T is OfLength[N])()

case class Tensor[T <: Tuple : {Precise, TupleOf[Int]}](tracked val dims: T):
  def reshape[T2 <: Tuple : {TupleOf[Int] as ev1, OfLength[Size[dims.type]]}](newDims: T2) =
    Tensor(newDims)(using ev1).asInstanceOf[Tensor[T2]] // error
    //                    ^^^
    // Found:    (ev1 : TupleOf[Int]{type Self = T2})
    // Required: Precise{type Self² = (newDims : T2)}

@main def Test =
  val v1 = Tensor((1,2,3): (1,2,3)) // Doesn't `Precise` affect tuples?
  summon[Size[v1.dims.type] =:= 3]
  summon[v1.dims.type is OfLength[3]]
  v1.reshape((1,3,2): (1,3,2))
