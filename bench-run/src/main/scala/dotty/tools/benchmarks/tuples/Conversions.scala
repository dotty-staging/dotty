package dotty.tools.benchmarks.tuples

import org.openjdk.jmh.annotations._

@State(Scope.Thread)
class Conversions {
  @Param(Array("1"))
  var size: Int = _
  var tuple: Tuple = _
  var array: Array[Object] = _
  var iarray: IArray[Object] = _

  @Setup
  def setup(): Unit = {
    tuple = ()

    for (i <- 1 to size)
      tuple = "elem" *: tuple

    array = Array.fill(size)("elem")
    iarray = IArray.fill(size)("elem")
  }

  @Benchmark
  def tupleToArray(): Array[Object] = {
    runtime.Tuple.toArray(tuple.asInstanceOf[Unit | Product])
  }

  @Benchmark
  def tupleToIArray(): IArray[Object] = {
    runtime.Tuple.toIArray(tuple.asInstanceOf[Unit | Product])
  }

  @Benchmark
  def tupleFromArray(): Unit | Product = {
    runtime.Tuple.fromArray(array)
  }

  @Benchmark
  def tupleFromIArray(): Unit | Product = {
    runtime.Tuple.fromIArray(iarray)
  }

  @Benchmark
  def productToArray(): Array[Object] = {
    runtime.Tuple.productToArray(tuple.asInstanceOf[Product])
  }

  @Benchmark
  def cloneArray(): Array[Object] = {
    array.clone()
  }
}
