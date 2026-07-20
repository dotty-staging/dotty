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

  // Test: duplicates from expansion are preserved in List result
  // (flatmap semantics: each element expands to multiple elements)
  val dupExpanded = List(1, 2).groupFlatMap(identity)(x => List(x, x))
  assert(dupExpanded == Map(1 -> List(1, 1), 2 -> List(2, 2)))

  // Test: duplicates across different elements in same group are preserved
  // (encounter order + flatMap semantics)
  val crossDup = List("aa", "ab").groupFlatMap(_.head)(s => List(s.length))
  assert(crossDup == Map('a' -> List(2, 2)))

  // Test: multiple groups with mixed expansion sizes
  val mixed = List(1, 2, 3, 4).groupFlatMap(_ % 2)(x => List(x, x * 10, x * 100))
  assert(mixed(1) == List(1, 10, 100, 3, 30, 300))
  assert(mixed(0) == List(2, 20, 200, 4, 40, 400))

  // Test: empty expansion creates empty group (not suppressed)
  // This is the documented behavior
  val emptyExpansions = List(1, 2, 3).groupFlatMap(identity)(x => if x == 2 then Nil else List(x))
  // Note: key 2 gets an empty group (with a builder that was created but never added to)
  assert(emptyExpansions(1) == List(1))
  assert(emptyExpansions(2) == List.empty) // Empty group should exist
  assert(emptyExpansions(3) == List(3))
