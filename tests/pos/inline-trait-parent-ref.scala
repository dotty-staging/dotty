inline trait A[T](val x: T):
  def foo: T = x

trait B extends A[Int]:
    val y = 1

def h(x: B) = x.foo
