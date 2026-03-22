package SpecializedTraitsBenchmark

// This trait isn't really necessary but just wanted to check that the trait virtual call wasn't
// slowing us down in ours relative to the manual version which it's not.
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


object TestBench:
  val v1 = Array.fill(100_000_000) {math.round(math.random.floatValue * 4)}
  val v2 = Array.fill(100_000_000) {math.round(math.random.floatValue * 4)}
  
  val a1 = Vec(v1)
  val b1 = Vec(v2)

  val a2 = VecGeneric[Int](v1)
  val b2 = VecGeneric[Int](v2)

  val a3 = new VecSpec[Int](v1) {}
  val b3 = new VecSpec[Int](v2) {}

  def benchManuallySpec = 
    val result = a1.scalarProduct(b1)
    println(s"Got ${result}")
  
  def benchGeneric = 
    val result2 = a2.scalarProduct(b2)
    println(s"Got ${result2}")

  def benchOurs = 
    val result = a3.scalarProduct(b3)
    println(s"Got ${result}")


// scala-cli --power package --assembly --preamble=false tests/run/benchmark-jmh-compatible.scala -S 3.8.3-RC1-bin-SNAPSHOT-nonbootstrapped --verbose
// mv benchmark-jmh-compatible.jar ../bench/test/benchmark.jar

// In benchmark project intellij
// mvn install:install-file   -Dfile=benchmark.jar   -DgroupId=benchmark   -DartifactId=benchmark   -Dversion=1.0   -Dpackaging=jar
// mvn clean compile
// Invalidate Caches and Restart
// Run benchmarks!
