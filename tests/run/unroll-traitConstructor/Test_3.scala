//> using options -experimental

@main def Test: Unit =
  val bar = Bar(1, "abc", 2, 20)
  assert(bar.res == 23)

  val foo = Foo(1, "abc", 2)
  assert(foo.res == 13)
end Test
