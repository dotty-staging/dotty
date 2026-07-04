@main def Test: Unit =
  val xs = List(1, 2, 3, 4, 5)

  // takeTo includes the first matching element, dropTo excludes it
  assert(xs.takeTo(_ == 3) == List(1, 2, 3))
  assert(xs.dropTo(_ == 3) == List(4, 5))

  // together they reassemble the input
  assert(xs.takeTo(_ == 3) ++ xs.dropTo(_ == 3) == xs)

  // no match: takeTo returns the whole input, dropTo returns empty
  assert(xs.takeTo(_ == 42) == xs)
  assert(xs.dropTo(_ == 42) == Nil)

  // only the first matching element is the terminator
  assert(List(1, 2, 1, 2).takeTo(_ == 2) == List(1, 2))
  assert(List(1, 2, 1, 2).dropTo(_ == 2) == List(1, 2))

  // empty input
  assert(List.empty[Int].takeTo(_ => true) == Nil)
  assert(List.empty[Int].dropTo(_ => true) == Nil)

  // results use the receiver's own collection type
  val v: Vector[Int] = Vector(1, 2, 3).takeTo(_ == 2)
  assert(v == Vector(1, 2))

  // iterators: lazy and single-pass; elements after the terminator are not consumed
  val it = Iterator(1, 2, 3, 4, 5)
  assert(it.takeTo(_ == 2).toList == List(1, 2))
  assert(it.toList == List(3, 4, 5))
  assert(Iterator(1, 2, 3).dropTo(_ == 1).toList == List(2, 3))
  assert(Iterator.empty[Int].takeTo(_ => true).toList == Nil)
  assert(Iterator.empty[Int].dropTo(_ => true).toList == Nil)

  // works on unbounded sources, unlike a span-based emulation of takeTo
  assert(Iterator.from(1).takeTo(_ == 3).toList == List(1, 2, 3))
  assert(LazyList.from(1).takeTo(_ == 3).toList == List(1, 2, 3))
  assert(LazyList.from(1).dropTo(_ == 2).head == 3)
