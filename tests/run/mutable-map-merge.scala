import scala.collection.mutable

@main def Test: Unit =
  // the motivating pattern: accumulate into a key (combine-or-insert)
  val counts = mutable.Map.empty[String, Int]
  for word <- List("a", "b", "a", "c", "a") do counts.merge(word, 1, _ + _)
  assert(counts == mutable.Map("a" -> 3, "b" -> 1, "c" -> 1))

  // returns the value now associated with the key
  val m = mutable.Map("x" -> 10)
  assert(m.merge("x", 5, _ + _) == 15)
  assert(m.merge("y", 5, _ + _) == 5)
  assert(m == mutable.Map("x" -> 15, "y" -> 5))

  // an exception in the remapping function leaves the map unchanged
  val m2 = mutable.Map("k" -> 1)
  try
    m2.merge("k", 2, (_, _) => throw new IllegalStateException("boom"))
    assert(false)
  catch case _: IllegalStateException => ()
  assert(m2 == mutable.Map("k" -> 1))

  // the zio-kafka use case: maximum offset per partition
  val maxOffsets = mutable.Map.empty[Int, Long]
  for (partition, offset) <- List((0, 5L), (1, 3L), (0, 9L), (0, 7L), (1, 4L)) do
    maxOffsets.merge(partition, offset, math.max)
  assert(maxOffsets == mutable.Map(0 -> 9L, 1 -> 4L))

  // available on every mutable.Map implementation
  val lhm = mutable.LinkedHashMap("a" -> "x")
  lhm.merge("a", "y", _ + _)
  lhm.merge("b", "z", _ + _)
  assert(lhm.toList == List("a" -> "xy", "b" -> "z")) // insertion order kept
  val tm = mutable.TreeMap(3 -> "c")
  tm.merge(1, "a", _ + _)
  assert(tm.toList == List(1 -> "a", 3 -> "c"))
