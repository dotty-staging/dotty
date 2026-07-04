@main def Test: Unit =
  val m = Map("a" -> 1, "b" -> 2)

  // present key: value plus the map without it
  val (v1, m1) = m.getAndRemove("a")
  assert(v1 == Some(1))
  assert(m1 == Map("b" -> 2))

  // absent key: None plus the original map
  val (v2, m2) = m.getAndRemove("zzz")
  assert(v2 == None)
  assert(m2 == m)

  // the receiver is unchanged (immutable)
  assert(m == Map("a" -> 1, "b" -> 2))

  // returned map has the receiver's own type
  val sm = scala.collection.immutable.SortedMap(3 -> "c", 1 -> "a")
  val (v3, sm1) = sm.getAndRemove(3)
  assert(v3 == Some("c"))
  val sorted: scala.collection.immutable.SortedMap[Int, String] = sm1
  assert(sorted.toList == List(1 -> "a"))

  // typical usage: work-queue style take
  var queue = Map("job1" -> 10, "job2" -> 20)
  val (job, rest) = queue.getAndRemove("job1")
  queue = rest
  assert(job == Some(10) && queue == Map("job2" -> 20))
