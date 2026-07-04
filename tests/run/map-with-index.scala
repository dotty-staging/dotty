@main def Test: Unit =
  assert(List("a", "b", "c").mapWithIndex((s, i) => s"$i:$s") == List("0:a", "1:b", "2:c"))
  assert(List.empty[String].mapWithIndex((s, i) => s"$i:$s") == Nil)

  // returns the receiver's collection type, like map
  val v: Vector[Int] = Vector(10, 20).mapWithIndex(_ + _)
  assert(v == Vector(10, 21))

  // indexing starts at zero and follows encounter order
  assert(List(5, 5, 5).mapWithIndex((_, i) => i) == List(0, 1, 2))

  // equivalent to the zipWithIndex spelling, without the intermediate tuples
  val xs = List("x", "y", "z")
  assert(xs.mapWithIndex((a, i) => (a, i)) == xs.zipWithIndex)

  // single pass, works lazily on views
  var evaluated = 0
  val view = List(1, 2, 3).view.mapWithIndex { (a, i) => evaluated += 1; a * i }
  assert(evaluated == 0)
  assert(view.toList == List(0, 2, 6))
