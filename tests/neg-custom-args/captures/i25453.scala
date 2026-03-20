import scala.caps.SharedCapability

class File extends SharedCapability
class Box[T] extends SharedCapability

trait Foo:
  def foo[A](f: A => Unit): A

def test(f: Foo) =
  val _: File = f.foo(_ => ()) // error
  val _: File = f.foo[File](_ => ()) // error

def testGeneric[U](f: Foo) =
  val _: Box[U] = f.foo(_ => ()) // error
  val _: Box[U] = f.foo[Box[U]](_ => ()) // error
