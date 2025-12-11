//> using options -experimental
@main def Test =
  Iterable.from({
    val it = Iterator.iterate(1)(_ + 1).take(10)
    it
  })
  .groupMapReduce(_ % 3)(identity)(_ + _).foreach(println)
