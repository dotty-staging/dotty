import scala.language.experimental.collectionLiterals
import scala.compiletime.ExpressibleAsCollectionLiteral

object Test:
  // element type errors point at the element
  val v: Vector[Int] = [1, "two"] // error

  // mutable collections, including Array, have no ambient instance:
  // the default Seq does not conform
  val buf: collection.mutable.ArrayBuffer[Int] = [1, 2] // error
  val arr: Array[Int] = [1, 2] // error

  // ambiguous instances are an error
  class MyColl
  given collA: ExpressibleAsCollectionLiteral[MyColl]:
    type Elem = Int
    inline def fromLiteral(inline xs: Int*): MyColl = MyColl()
  given collB: ExpressibleAsCollectionLiteral[MyColl]:
    type Elem = Int
    inline def fromLiteral(inline xs: Int*): MyColl = MyColl()
  val mc: MyColl = [1, 2] // error
