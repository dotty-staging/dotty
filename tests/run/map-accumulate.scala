@main def Test: Unit =
  // running totals: map elements while threading state
  val (totals, sum) = List(1, 2, 3, 4).mapAccumulate(0)((a, acc) => (acc + a, acc + a))
  assert(totals == List(1, 3, 6, 10))
  assert(sum == 10)

  // label assignment: state is a counter
  val (labelled, next) = List("a", "b").mapAccumulate(100)((s, n) => (s"$s#$n", n + 1))
  assert(labelled == List("a#100", "b#101"))
  assert(next == 102)

  // empty input: empty collection, initial state unchanged
  val (empty, z) = List.empty[Int].mapAccumulate(42)((a, s) => (a, s + 1))
  assert(empty == Nil && z == 42)

  // mapped collection uses the receiver's collection type, like map
  val (vec, _) = Vector(1, 2, 3).mapAccumulate("")((a, s) => (s.length + a, s + a))
  assert(vec == Vector(1, 3, 5))

  // encounter order is preserved; single strict pass
  var order = List.empty[Int]
  List(1, 2, 3).mapAccumulate(())((a, s) => { order ::= a; (a, s) })
  assert(order == List(3, 2, 1))

  // the foldLeft equivalence the review pointed out, for comparison
  val viaFold = List(1, 2, 3, 4).foldLeft((List.empty[Int], 0)) { case ((acc, s), a) =>
    ((s + a) :: acc, s + a)
  }
  assert(viaFold._1.reverse == totals && viaFold._2 == sum)
