import scala.compiletime.*

trait Head[T]:
  def head(t: T): Any

transparent inline def headOfTuple[T <: Tuple]: Head[T] =
  inline erasedValue[T] match
    case _: (t *: ts) => (
      new Head[t *: ts] {
        def head(tup: t *: ts): Any =
          tup.head
      }
    ).asInstanceOf[Head[T]]

object Test:
  def main(args: Array[String]): Unit =
    headOfTuple[(Int, String)].head((1, "hola mundo"))
