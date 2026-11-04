class C:
  def argOfT[@specialized(Int) T](x: T): Unit =
    println(n)

  def returnsArrayOfT[@specialized(Specializable.Args) T]()(using ClassTag[T]): Array[T] =
    new Array[T](10)

  def argFunctionOfT[@specialized(Unit) T](x: T, f: T => Unit): Unit =
    f(x)
