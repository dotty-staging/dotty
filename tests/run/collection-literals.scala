import scala.language.experimental.collectionLiterals

@main def Test =
  val a: Seq[Int] = [1, 2, 3]
  assert(a == Seq(1, 2, 3))

  val b: Vector[Int] = [1, 2, 3]
  assert(b == Vector(1, 2, 3))
  assert(b.isInstanceOf[Vector[?]])

  val m: Map[String, Int] = ["a" -> 1, "b" -> 2]
  assert(m == Map("a" -> 1, "b" -> 2))

  val nested: Vector[Vector[Int]] = [[1, 0], [0, 1]]
  assert(nested == Vector(Vector(1, 0), Vector(0, 1)))

  // untargeted default is immutable Seq; pairs do not become a Map
  val deflt = [1, 2, 1 + 1]
  assert(deflt == Seq(1, 2, 2))
  val pairs = ["a" -> 1, "a" -> 2]
  assert(pairs.length == 2) // a Map would have swallowed the duplicate key

  val empty: Map[String, Int] = []
  assert(empty.isEmpty)

  val iarr: IArray[Int] = [1, 2, 3]
  assert(iarr.sum == 6)

  locally {
    // mutable targets, including Array, require the explicit import
    import scala.compiletime.ExpressibleAsCollectionLiteral.mutableLiterals.given
    val arr: Array[Int] = [1, 2, 3]
    assert(arr.sum == 6)
  }

  def dot(xs: Vector[Int], ys: Vector[Int]): Int = xs.lazyZip(ys).map(_ * _).sum
  assert(dot([1, 2], [3, 4]) == 11)

  println("ok")
