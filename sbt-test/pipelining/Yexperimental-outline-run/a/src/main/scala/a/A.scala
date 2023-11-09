package a

object A {
  val foo: (1,2,3) = (1,2,3)

  def main(args: Array[String]): Unit =
    assert(foo(0) == 1)
    assert(foo(1) == 2)
    assert(foo(2) == 3)
}
