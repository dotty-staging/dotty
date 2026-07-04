@main def Test: Unit =
  val xs = List(1, 2, 3)
  val ys = List("a")

  // shorter side is padded with its default
  assert(xs.lazyZipAll(ys)(0, "z").map((n, s) => s"$n$s") == List("1a", "2z", "3z"))
  assert(ys.lazyZipAll(xs)("z", 0).map((s, n) => s"$s$n") == List("a1", "z2", "z3"))

  // equal lengths need no padding
  assert(xs.lazyZipAll(List(4, 5, 6))(0, 0).map(_ + _) == List(5, 7, 9))

  // empty sides
  assert(List.empty[Int].lazyZipAll(xs)(0, -1).map(_ + _) == List(1, 2, 3))
  assert(xs.lazyZipAll(List.empty[Int])(0, -1).map(_ - _) == List(2, 3, 4))
  assert(List.empty[Int].lazyZipAll(List.empty[Int])(0, 0).map(_ + _) == Nil)

  // strict ops build the receiver's collection type, like lazyZip
  val v: Vector[Int] = Vector(1, 2).lazyZipAll(List(10, 20, 30))(0, 0).map(_ + _)
  assert(v == Vector(11, 22, 30))

  // lazy: elements are not consumed until a strict operation runs
  var evaluated = 0
  val lz = LazyList.from(1).map { i => evaluated += 1; i }.take(3).lazyZipAll(List("a"))(0, "?")
  assert(evaluated == 0 || evaluated == 1) // building the decorator forces at most the head
  assert(lz.map((n, s) => s"$n$s").toList == List("1a", "2?", "3?"))

  // chaining with lazyZip still works (result pairs)
  val pairs = xs.lazyZipAll(ys)(0, "z").map((a, b) => (a, b))
  assert(pairs == List((1, "a"), (2, "z"), (3, "z")))
