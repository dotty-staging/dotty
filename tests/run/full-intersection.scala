@main def Test: Unit =
  val a = Set(1, 2, 3, 4)
  val b = Set(3, 4, 5, 6)

  // one call returns (only in this, in both, only in that)
  val (onlyA, both, onlyB) = a.fullIntersection(b)
  assert(onlyA == Set(1, 2))
  assert(both == Set(3, 4))
  assert(onlyB == Set(5, 6))

  // agrees with the three separate operations it replaces
  assert(onlyA == a.diff(b))
  assert(both == a.intersect(b))
  assert(onlyB == b.diff(a))

  // disjoint, equal, and empty cases
  assert(Set(1).fullIntersection(Set(2)) == (Set(1), Set(), Set(2)))
  assert(a.fullIntersection(a) == (Set(), a, Set()))
  assert(Set.empty[Int].fullIntersection(b) == (Set(), Set(), b))
  assert(a.fullIntersection(Set.empty[Int]) == (a, Set(), Set()))

  // first two components use the receiver's own set type
  val sa = scala.collection.immutable.SortedSet(3, 1, 2)
  val (so, sb, st) = sa.fullIntersection(Set(2, 9))
  val sortedOnly: scala.collection.immutable.SortedSet[Int] = so
  assert(so.toList == List(1, 3) && sb.toList == List(2) && st == Set(9))

  // element type widening in the third component
  val (wo, wb, wt) = Set(1).fullIntersection(Set[AnyVal](1, 2.5))
  assert(wo == Set() && wb == Set(1) && wt == Set[AnyVal](2.5))
