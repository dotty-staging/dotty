import scala.collection.{mutable, immutable}

@main def Test: Unit =
  val a = Map("x" -> 1, "y" -> 2)
  val b = Map("y" -> 20, "z" -> 30)

  // overlapping keys are combined; keys in only one map keep their value
  assert(a.unionWith(b, (_, l, r) => l + r) == Map("x" -> 1, "y" -> 22, "z" -> 30))

  // the key is passed to the combining function
  assert(a.unionWith(b, (k, l, r) => if k == "y" then l else r) == Map("x" -> 1, "y" -> 2, "z" -> 30))

  // argument order: second argument comes from the receiver, third from `that`
  assert(a.unionWith(b, (_, l, _) => l)("y") == 2)
  assert(a.unionWith(b, (_, _, r) => r)("y") == 20)

  // disjoint maps behave like concat
  assert(Map("a" -> 1).unionWith(Map("b" -> 2), (_, l, _) => l) == Map("a" -> 1, "b" -> 2))

  // empty on either side
  assert(Map.empty[String, Int].unionWith(b, (_, l, _) => l) == b)
  assert(a.unionWith(Map.empty[String, Int], (_, l, _) => l) == a)

  // value widening
  val widened: Map[String, AnyVal] = a.unionWith(Map("y" -> 2.5): Map[String, AnyVal], (_, _, r) => r)
  assert(widened == Map[String, AnyVal]("x" -> 1, "y" -> 2.5))

  // returns the receiver's own map type; mutable maps produce a NEW map
  val ma = mutable.Map("x" -> 1, "y" -> 2)
  val mr: mutable.Map[String, Int] = ma.unionWith(mutable.Map("y" -> 5), (_, l, r) => l max r)
  assert(mr == mutable.Map("x" -> 1, "y" -> 5))
  assert(ma == mutable.Map("x" -> 1, "y" -> 2)) // receiver unchanged

  // unionWithOption: the combining function may remove overlapping keys
  assert(a.unionWithOption(b, (_, _, _) => None) == Map("x" -> 1, "z" -> 30))
  assert(a.unionWithOption(b, (_, l, r) => Some(l + r)) == Map("x" -> 1, "y" -> 22, "z" -> 30))
  // only overlapping keys can be removed; "x" is kept because f never sees it
  assert(a.unionWithOption(b, (k, l, r) => if l < 2 then None else Some(l * r)) == Map("x" -> 1, "y" -> 40, "z" -> 30))

  // existing specialized unionWith on IntMap keeps working (overload, not override)
  val im = immutable.IntMap(1 -> "a", 2 -> "b").unionWith(immutable.IntMap(2 -> "B", 3 -> "c"), (_, l, r) => l + r)
  assert(im == immutable.IntMap(1 -> "a", 2 -> "bB", 3 -> "c"))
