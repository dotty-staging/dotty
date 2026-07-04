@main def Test: Unit =
  assert(List(1, 2, 1, 3, 1).frequencies == Map(1 -> 3, 2 -> 1, 3 -> 1))
  assert(List.empty[String].frequencies == Map.empty[String, Int])

  // any IterableOnce works, including strings (via WrappedString) and sets
  assert("mississippi".frequencies == Map('m' -> 1, 'i' -> 4, 's' -> 4, 'p' -> 2))
  assert(Set("x", "y").frequencies == Map("x" -> 1, "y" -> 1))

  // single pass: an iterator is consumed exactly once
  val it = Iterator("a", "b", "a")
  assert(it.frequencies == Map("a" -> 2, "b" -> 1))
  assert(!it.hasNext)

  // the key type can be widened explicitly (Map is invariant in its keys)
  val nums: List[Int] = List(1, 1, 2)
  val wide: Map[AnyVal, Int] = nums.frequencies[AnyVal]
  assert(wide == Map(1 -> 2, 2 -> 1))

  // equivalent to the groupMapReduce spelling, in one pass and fewer words
  val words = List("the", "quick", "the", "lazy", "the")
  assert(words.frequencies == words.groupMapReduce(identity)(_ => 1)(_ + _))
