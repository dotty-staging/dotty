trait VecT:
  def length: Int
  def apply(i: Int): Int
  def scalarProduct(other: VecT): Int

class Vec(elems: Array[Int]) extends VecT:
  private val num = summon[Numeric[Int]]
  private val x = 1

  def length = elems.length

  def apply(i: Int): Int = elems(i)

  def scalarProduct(other: VecT): Int =
    require(this.length == other.length)
    var result = num.fromInt(0)
    for i <- 0 until length do
      result = num.plus(result, num.times(this(i), other(i)))
    result

class VecGeneric[T: Numeric](elems: Array[T]):
  private val num = summon[Numeric[T]]
  private val x = 1

  def length = elems.length

  def apply(i: Int): T = elems(i)

  def scalarProduct(other: VecGeneric[T]): T =
    require(this.length == other.length)
    var result = num.fromInt(0)
    for i <- 0 until length do
      result = num.plus(result, num.times(this(i), other(i)))
    result

inline trait VecSpec[T: {Specialized, Numeric}](elems: Array[T]):
  private val num = summon[Numeric[T]]
  private val x = 1

  def length = elems.length

  def apply(i: Int): T = elems(i)

  def scalarProduct(other: VecSpec[T]): T =
    require(this.length == other.length)
    var result = num.fromInt(0)
    for i <- 0 until length do
      result = num.plus(result, num.times(this(i), other(i)))
    result

@main def main =
  val v1 = Array.fill(100_000_000) {math.round(math.random.floatValue * 4)}
  val v2 = Array.fill(100_000_000) {math.round(math.random.floatValue * 4)}
  
  // println("------------ Ours ------------")
  // val a4 = new VecSpec[Int](v1) {}
  // val b4 = new VecSpec[Int](v2) {}
  // val start4 = System.nanoTime()
  // val result4 = a4.scalarProduct(b4)
  // println(s"Got ${result4}")
  // val end4 = System.nanoTime()
  // println(s"Took ${(end4 - start4).toFloat / 1_000_000_000.toFloat} seconds")

  // println("------------ Generic ------------")
  // val a2 = VecGeneric[Int](v1)
  // val b2 = VecGeneric[Int](v2)
  // val start2 = System.nanoTime()
  // val result2 = a2.scalarProduct(b2)
  // println(s"Got ${result2}")
  // val end2 = System.nanoTime()
  // println(s"Took ${(end2 - start2).toFloat / 1_000_000_000.toFloat} seconds") 

  println("------------ Manually Specialized ------------")
  val a1 = Vec(v1)
  val b1 = Vec(v2)
  val start1 = System.nanoTime()
  val result1 = a1.scalarProduct(b1)
  println(s"Got ${result1}")
  val end1 = System.nanoTime()
  println(s"Took ${(end1 - start1).toFloat / 1_000_000_000.toFloat} seconds")

  // println("------------ Ours ------------")
  // val a3 = new VecSpec[Int](v1) {}
  // val b3 = new VecSpec[Int](v2) {}
  // val start3 = System.nanoTime()
  // val result3 = a3.scalarProduct(b3)
  // println(s"Got ${result3}")
  // val end3 = System.nanoTime()
  // println(s"Took ${(end3 - start3).toFloat / 1_000_000_000.toFloat} seconds")    

// Think that the Generic case is really messing with the JIT
// When we put it in we get very inconsistent results where usually the second Ours
// is 0.7 or 1.0 instead of 0.3, but sometimes it's the Manually Specialized that gets messed up.
// If on the other hand we add -XInt and reduce to 1 million instead of 10 million the problem goes away.
// If we run each case independently we get the expected results. 

// For comparison (because why not)
// We get comparable results to C++ with the manually specialized and ours versions
// BUT if you put on -Ofast then C++ destroys us.
