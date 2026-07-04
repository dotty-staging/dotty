import scala.collection.immutable.SeqMap

@main def Test: Unit =
  val xs = List(3, 1, 4, 1, 5, 9, 2, 6)

  // keys appear in first-seen order; values inside each group keep input order
  val g = xs.groupByOrdered(_ % 2)
  assert(g.keys.toList == List(1, 0))
  assert(g(1) == List(3, 1, 1, 5, 9))
  assert(g(0) == List(4, 2, 6))
  val typed: SeqMap[Int, List[Int]] = g // static result type is SeqMap

  // single-argument overload keeps whole elements with the receiver's type
  val vg: SeqMap[Boolean, Vector[Int]] = Vector(1, 2, 3).groupByOrdered(_ > 1)
  assert(vg.keys.toList == List(false, true))
  assert(vg(true) == Vector(2, 3))

  // two-argument overload: the SQL inner-join re-nesting case
  val rows = List(("order1", "itemA"), ("order2", "itemB"), ("order1", "itemC"))
  val nested = rows.groupByOrdered(_._1, _._2)
  assert(nested.keys.toList == List("order1", "order2"))
  assert(nested == SeqMap("order1" -> List("itemA", "itemC"), "order2" -> List("itemB")))

  // Opt overload: the left/full outer-join case — a None value adds nothing,
  // but the parent key is still created with an empty group
  val leftJoinRows = List(("p1", Some("c1")), ("p2", None), ("p1", Some("c2")))
  val opt = leftJoinRows.groupByOrderedOpt(_._1, _._2)
  assert(opt.keys.toList == List("p1", "p2"))
  assert(opt("p1") == List("c1", "c2"))
  assert(opt("p2") == List())

  // empty input
  assert(List.empty[Int].groupByOrdered(identity) == SeqMap.empty)

  // groupByOrdered agrees with groupBy up to ordering
  assert(g.toMap == xs.groupBy(_ % 2))
