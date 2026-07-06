import scala.language.experimental.collectionLiterals

object Test:
  // target-typed elaboration through ExpressibleAsCollectionLiteral
  val a: Seq[Int] = [1, 2, 3]
  val b: List[Int] = [1, 2, 3]
  val c: Vector[String] = ["a", "b"]
  val d: Set[Int] = [1, 2, 3]
  val e: Map[String, Int] = ["a" -> 1, "b" -> 2]
  val iarr: IArray[Int] = [1, 2, 3]

  // nested literals: elements get their expected type from the instance
  val nested: Vector[Vector[Int]] = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
  val nestedMap: Map[Int, List[Int]] = [1 -> [1, 2], 2 -> [3]]

  // maps have no special syntax: any pair-typed element works
  val kv: (String, Int) = "a" -> 1
  val e2: Map[String, Int] = [kv, "b" -> 2]

  // empty literals work wherever there is a target
  val empty1: Seq[Int] = []
  val empty2: Map[String, Int] = []
  val empty3: Vector[String] = []

  // fixed default: no usable expected type means immutable Seq
  val deflt = [1, 2, 3]
  val deflt1: Seq[Int] = deflt
  val defltPairs = ["a" -> 1]
  val defltPairs1: Seq[(String, Int)] = defltPairs
  val mapped = [1, 2, 3].map(_ + 1)
  val infix = [1, 2] ++ [3, 4]

  // literals as arguments
  def sum(xs: List[Int]): Int = xs.sum
  val s = sum([1, 2, 3])
  def dot(xs: Vector[Int], ys: Vector[Int]): Int = xs.lazyZip(ys).map(_ * _).sum
  val p = dot([1, 2], [3, 4])

  // unconstrained type parameter: default applies
  def id[T](x: T): T = x
  val idd: Seq[Int] = id([1, 2, 3])

  // polymorphic function literals still parse
  val polyLam = [T] => (x: T) => x
  val one = polyLam(1)

  // mutable targets require the explicit import
  object mutableTargets:
    import scala.compiletime.ExpressibleAsCollectionLiteral.mutableLiterals.given
    import scala.collection.mutable
    val arr: Array[Int] = [1, 2, 3]
    val buf: mutable.ArrayBuffer[Int] = [1, 2, 3]
    val mset: mutable.Set[Int] = [1, 2]
    val mmap: mutable.Map[String, Int] = ["a" -> 1]
