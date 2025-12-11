//> using options -experimental
@main def Test =
  Iterator.iterate(1)(_ + 1).take(10).groupMapReduce(_ % 3)(identity)(_ + _).foreach(println)
