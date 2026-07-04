@main def Test: Unit =
  // values become keys; original keys sharing a value are grouped into a set
  val m = Map("a" -> 1, "b" -> 2, "c" -> 1)
  assert(m.invert == Map(1 -> Set("a", "c"), 2 -> Set("b")))

  // empty map
  assert(Map.empty[String, Int].invert == Map.empty)

  // injective maps invert to singleton groups
  assert(Map("x" -> 1, "y" -> 2).invert == Map(1 -> Set("x"), 2 -> Set("y")))

  // the value type can be widened explicitly (it becomes the invariant key type)
  val wide: Map[AnyVal, Set[String]] = m.invert[AnyVal]
  assert(wide(1) == Set("a", "c"))

  // defined on collection.MapOps, so mutable maps get it too (result is immutable)
  val mm = scala.collection.mutable.Map(1 -> "x", 2 -> "x", 3 -> "y")
  assert(mm.invert == Map("x" -> Set(1, 2), "y" -> Set(3)))
  assert(mm.size == 3) // receiver untouched

  // many-to-one relationship: group hosts by datacenter
  val hostToDc = Map("h1" -> "eu", "h2" -> "us", "h3" -> "eu")
  assert(hostToDc.invert == Map("eu" -> Set("h1", "h3"), "us" -> Set("h2")))
