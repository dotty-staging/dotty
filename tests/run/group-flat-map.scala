@main def Test: Unit =
  // group words by first letter, expanding each word into its characters
  val words = List("apple", "avocado", "banana")
  val grouped = words.groupFlatMap(_.head)(_.toList.distinct)
  assert(grouped == Map(
    'a' -> List('a', 'p', 'l', 'e', 'a', 'v', 'o', 'c', 'd'),
    'b' -> List('b', 'a', 'n')))

  // zero-output elements contribute nothing (but grouping still occurs via other elements)
  val sparse = List(1, 2, 3, 4).groupFlatMap(_ % 2)(a => if a > 2 then List(a, a * 10) else Nil)
  assert(sparse == Map(1 -> List(3, 30), 0 -> List(4, 40)))

  // encounter order inside each group is preserved
  val ord = List(1, 3, 2, 4).groupFlatMap(_ % 2)(List(_))
  assert(ord(1) == List(1, 3) && ord(0) == List(2, 4))

  // group values use the receiver's collection type CC, like groupMap
  val vecs: Map[Boolean, Vector[Int]] = Vector(1, 2, 3).groupFlatMap(_ > 1)(a => Vector(a))
  assert(vecs == Map(false -> Vector(1), true -> Vector(2, 3)))

  // empty input
  assert(List.empty[Int].groupFlatMap(identity)(List(_)) == Map.empty)

  // equivalence with groupMap + flatten, in one pass
  val xs = List("ab", "c", "de")
  assert(xs.groupFlatMap(_.length)(_.toList) ==
    xs.groupMap(_.length)(_.toList).view.mapValues(_.flatten).toMap)
