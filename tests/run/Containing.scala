
import scala.language.experimental.modularity
import scala.TmpPredef.*
import scala.Containing


trait Showable extends TypeClass:
  extension (self: Self) def show: String

object Showable:

  given String is Showable:
    extension (self: String) def show: String = self

  given Int is Showable:
    extension (self: Int) def show: String = s"My int is $self"

  given EmptyTuple is Showable:
    extension (self: EmptyTuple) def show: String = "EmptyTuple"
  given [H : Showable, T <: Tuple : Showable] => (H *: T) is Showable:
    extension (self: H *: T) def show: String = s"${self.head.show} andThen ${self.tail.show}"

end Showable


trait Numeric extends TypeClass:
  extension (self: Self) def toInt: Int

object Numeric:
  given [A : math.Numeric] => A is Numeric:
    extension (self: A) def toInt: Int = A.toInt(self)
end Numeric


trait Serializable extends TypeClass:
  type Output
  extension (self: Self) def serialized: Output

object Serializable:
  type To[O] = Serializable { type Output = O }

  given Int is Serializable as intIsSerializableToByte:
    type Output = Byte
    extension (self: Int) def serialized: Byte = self.toByte

  given Int is Serializable as intIsSerializableToString:
    type Output = String
    extension (self: Int) def serialized: String = self.toString

end Serializable


def showTwice(x: Containing[Showable]) = x.value.show + x.value.show

def showAll(xs: Seq[Containing[Showable]]) =
  xs.map(_.value.show)

def showPairs(xs: Containing[Showable]{type Value <: Tuple} *) = xs.collect:
  case x if x.value.size == 2 => x.value.show


@main def Test =

  // Constructing
  Containing[Showable]("Hello")[String]
  Containing[Showable]("Hello")
  val x: Containing[Showable] = Containing("Hello")

  // Deconstructing
  assert(x.witness.show(x.value) == "Hello")
  assert(x.value.show == "Hello")

  // Passing
  assert(showTwice(x) == "HelloHello")

  showAll(Seq(
    Containing("Hello again"),
    "world".as[Containing[Showable]],
    23.as
  ))

  locally:
    import scala.Containing.constructor
    showPairs((1, 2), (3, 4, 5), (6, 7), EmptyTuple)
