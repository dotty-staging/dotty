package dotty.tools.benchmarks.tuples

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
class Concat {
  @Param(Array("0 0"))
  var sizes: String = _
  var tuple1: Tuple = _
  var tuple2: Tuple = _

  def tupleOfSize(n: Int): Tuple = {
    var t: Tuple = ()
    for (i <- 1 to n)
      t = "elem" *: t
    t
  }

  @Setup
  def setup(): Unit = {
    val size1 = sizes.split(' ')(0).toInt
    val size2 = sizes.split(' ')(1).toInt
    tuple1 = tupleOfSize(size1)
    tuple2 = tupleOfSize(size2)
  }

  @Benchmark
  def tupleConcat(): Unit | Product = {
    runtime.Tuple.concat(tuple1.asInstanceOf[Unit | Product], tuple2.asInstanceOf[Unit | Product])
  }
}
