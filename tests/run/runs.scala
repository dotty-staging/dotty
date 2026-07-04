@main def Test: Unit =
  val xs = List(1, 1, 2, 2, 2, 1, 3, 3)

  // runsBy: a new run starts when the discriminator value changes
  assert(xs.runsBy(identity).toList ==
    List((1, List(1, 1)), (2, List(2, 2, 2)), (1, List(1)), (3, List(3, 3))))

  // runsWith: runs alternate between p-true and p-false stretches
  assert(xs.runsWith(_ % 2 == 0).toList ==
    List(List(1, 1), List(2, 2, 2), List(1, 3, 3)))

  // matchingRuns: only the runs whose elements satisfy p
  assert(xs.matchingRuns(_ % 2 == 0).toList == List(List(2, 2, 2)))

  // empty and singleton inputs
  assert(List.empty[Int].runsBy(identity).toList == Nil)
  assert(List(7).runsBy(identity).toList == List((7, List(7))))
  assert(List.empty[Int].matchingRuns(_ => true).toList == Nil)

  // runs reassemble the input
  assert(xs.runsWith(_ >= 2).toList.flatten == xs)

  // runs use the receiver's own collection type
  val vruns: Iterator[Vector[Int]] = Vector(1, 1, 2).runsWith(_ == 1)
  assert(vruns.toList == List(Vector(1, 1), Vector(2)))

  // the motivating data problem: burst detection in ordered event data
  val log = List(
    "INFO  boot", "INFO  ready", "ERROR db down", "ERROR db retry", "INFO  db up")
  val bursts = log.runsBy(_.takeWhile(_ != ' ')).toList
  assert(bursts.map(_._1) == List("INFO", "ERROR", "INFO"))
  assert(bursts(1)._2.size == 2)

  // collapse consecutive duplicates (uniq)
  assert(List(1, 1, 2, 2, 1).runsBy(identity).map(_._1).toList == List(1, 2, 1))

  // the outer iterator is lazy: a run can be taken from an unbounded source
  val first = LazyList.from(1).runsBy(_ / 3).next()
  assert(first == (0, LazyList(1, 2)))
