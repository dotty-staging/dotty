import scala.collection.immutable.{SeqSet, VectorSet}

@main def Test: Unit =
  // insertion order is preserved; duplicates keep their first position
  val s = VectorSet(3, 1, 4, 1, 5, 9, 2, 6)
  assert(s.toList == List(3, 1, 4, 5, 9, 2, 6))
  assert(s.size == 7)
  assert(VectorSet(1, 2, 1, 3).toList == List(1, 2, 3))

  // incl appends new elements, keeps the position of existing ones
  assert((s + 7).toList == List(3, 1, 4, 5, 9, 2, 6, 7))
  assert((s + 4).toList == s.toList)

  // excl preserves the order of the remaining elements
  assert((s - 4).toList == List(3, 1, 5, 9, 2, 6))
  assert(((s - 4) + 4).toList == List(3, 1, 5, 9, 2, 6, 4)) // re-adding appends
  assert((s - 42) eq s)

  // membership and order-insensitive equality with other sets
  assert(s.contains(9) && !s.contains(42))
  assert(s == Set(1, 2, 3, 4, 5, 6, 9))

  // head/last/tail/init follow insertion order
  assert(s.head == 3 && s.last == 6)
  assert(s.tail.toList == List(1, 4, 5, 9, 2, 6))
  assert(s.init.toList == List(3, 1, 4, 5, 9, 2))

  // transformations preserve order and the VectorSet type
  val filtered: VectorSet[Int] = s.filter(_ % 2 == 1)
  assert(filtered.toList == List(3, 1, 5, 9))
  assert(s.map(_ * 10).toList == List(30, 10, 40, 50, 90, 20, 60))

  // SeqSet factory: default implementation is VectorSet
  val ss: SeqSet[String] = SeqSet("b", "a", "c")
  assert(ss.toList == List("b", "a", "c"))
  assert(ss.isInstanceOf[VectorSet[?]])
  assert(SeqSet.empty[Int].isEmpty)
  assert(SeqSet.from(List(2, 1)).toList == List(2, 1))

  // builder deduplicates while preserving first-seen order
  val b = VectorSet.newBuilder[Int]
  b += 5; b += 3; b += 5; b += 1
  assert(b.result().toList == List(5, 3, 1))

  // empty cases
  assert(VectorSet.empty[Int].toList == Nil)
  assert((VectorSet(1) - 1).isEmpty)

  // larger set exercises the underlying vector/tombstone machinery
  val big = VectorSet.from(1 to 100)
  assert(big.toList == (1 to 100).toList)
  assert((big - 50).toList == ((1 to 49) ++ (51 to 100)).toList)
  assert((big -- (1 to 99)).toList == List(100))
