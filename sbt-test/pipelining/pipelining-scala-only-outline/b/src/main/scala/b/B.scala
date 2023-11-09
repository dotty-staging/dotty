package b

import a.A

object B {
  val b: 2 = A.foo(1)

  // TODO: java.lang.ClassNotFoundException: b.B in run task (only with outline compiler?)
  def main(args: Array[String]): Unit =
    assert(A.foo(0) == 1)
    assert(A.foo(1) == 2)
    assert(A.foo(2) == 3)
}
