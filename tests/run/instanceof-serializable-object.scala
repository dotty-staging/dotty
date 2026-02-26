object Test:
  object Foo
  def foo: Any = Foo

  def main(args: Array[String]): Unit =
    println(Foo.isInstanceOf[Serializable])
    println(foo.isInstanceOf[Serializable])
