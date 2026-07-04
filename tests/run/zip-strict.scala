@main def Test: Unit =
  // knownSize fast path: Vector and Array both know their sizes
  val fast = Vector(1, 2, 3).zipStrict(Vector("a", "b", "c"))
  assert(fast.map(_.toList) == Some(List((1, "a"), (2, "b"), (3, "c"))))
  assert(Vector(1, 2).zipStrict(Vector("a")) == None)

  // sizes not known (List.knownSize == -1): verified by consumption
  assert(List(1, 2).zipStrict(List("a", "b")).map(_.toList) == Some(List((1, "a"), (2, "b"))))
  assert(List(1, 2, 3).zipStrict(List("a", "b")) == None)
  assert(List(1).zipStrict(List("a", "b", "c")) == None)

  // mixed known/unknown sizes
  assert(Vector(1, 2).zipStrict(List("a", "b")).map(_.toList) == Some(List((1, "a"), (2, "b"))))
  assert(List(1, 2).zipStrict(Vector("a")) == None)

  // two empty inputs align
  assert(Nil.zipStrict(Nil).map(_.toList) == Some(Nil))
  assert(Nil.zipStrict(List(1)) == None)

  // iterators work (defined on IterableOnceOps) and are consumed as needed
  assert(Iterator(1, 2).zipStrict(Iterator("a", "b")).map(_.toList) == Some(List((1, "a"), (2, "b"))))
  assert(Iterator(1, 2, 3).zipStrict(Iterator("a")) == None)

  // left-to-right pairing order is preserved
  val ord = List("x", "y", "z").zipStrict(List(1, 2, 3)).get.toList
  assert(ord == List(("x", 1), ("y", 2), ("z", 3)))
