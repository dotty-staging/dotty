// Exercises the RangeForeachOpt rewrite of `foreach` on statically
// constructed ranges: results, evaluation order and exceptions must be
// exactly those of the unoptimized `Range#foreach`.
object Test:

  def check(expected: Range)(loop: (Int => Unit) => Unit): Unit =
    val buf = collection.mutable.ListBuffer.empty[Int]
    loop(buf += _)
    assert(buf.toList == expected.toList, s"got ${buf.toList}, expected $expected = ${expected.toList}")

  def main(args: Array[String]): Unit =
    check(identity(1 to 10))(f => (1 to 10).foreach(f))
    check(identity(1 until 10))(f => (1 until 10).foreach(f))
    check(identity(10 to 1))(f => (10 to 1).foreach(f))
    check(identity(10 until 1))(f => (10 until 1).foreach(f))
    check(identity(1 to 1))(f => (1 to 1).foreach(f))
    check(identity(1 until 1))(f => (1 until 1).foreach(f))
    check(identity(10 to 0 by -1))(f => (10 to 0 by -1).foreach(f))
    check(identity(10 until 0 by -1))(f => (10 until 0 by -1).foreach(f))
    check(identity(1 to 10 by 3))(f => (1 to 10 by 3).foreach(f))
    check(identity(1 to 10 by 4))(f => (1 to 10 by 4).foreach(f))
    check(identity(1 until 10 by 3))(f => (1 until 10 by 3).foreach(f))
    check(identity(1 until 11 by 3))(f => (1 until 11 by 3).foreach(f))
    check(identity(3 to 5 by -1))(f => (3 to 5 by -1).foreach(f))
    check(identity(1.to(10, 2)))(f => 1.to(10, 2).foreach(f))
    check(identity(10.until(0, -3)))(f => 10.until(0, -3).foreach(f))
    check(identity(Range(1, 10)))(f => Range(1, 10).foreach(f))
    check(identity(Range(1, 10, 2)))(f => Range(1, 10, 2).foreach(f))
    check(identity(Range(10, 0, -3)))(f => Range(10, 0, -3).foreach(f))
    check(identity(Range.inclusive(1, 10)))(f => Range.inclusive(1, 10).foreach(f))
    check(identity(Range.inclusive(1, 10, 3)))(f => Range.inclusive(1, 10, 3).foreach(f))
    check(identity(Range.inclusive(10, 1, -3)))(f => Range.inclusive(10, 1, -3).foreach(f))

    // overflow boundaries: a naive `i <= end` loop would run forever or skip
    check(identity(Int.MaxValue - 3 to Int.MaxValue))(f => (Int.MaxValue - 3 to Int.MaxValue).foreach(f))
    check(identity(Int.MaxValue - 10 until Int.MaxValue by 3))(f => (Int.MaxValue - 10 until Int.MaxValue by 3).foreach(f))
    check(identity(Int.MinValue to Int.MinValue + 3))(f => (Int.MinValue to Int.MinValue + 3).foreach(f))
    check(identity(Int.MinValue until Int.MinValue + 10 by 4))(f => (Int.MinValue until Int.MinValue + 10 by 4).foreach(f))
    check(identity(Int.MinValue to Int.MaxValue by (1 << 28)))(f => (Int.MinValue to Int.MaxValue by (1 << 28)).foreach(f))
    check(identity(Int.MaxValue to Int.MinValue by -(1 << 28)))(f => (Int.MaxValue to Int.MinValue by -(1 << 28)).foreach(f))
    check(identity(Int.MinValue to Int.MaxValue by Int.MaxValue))(f => (Int.MinValue to Int.MaxValue by Int.MaxValue).foreach(f))

    // non-literal operands
    val a = 3
    var b = 17
    var s = 3
    check(identity(a to b by s))(f => (a to b by s).foreach(f))
    check(identity(a until b by s))(f => (a until b by s).foreach(f))
    check(identity(Range(b, a, -s)))(f => Range(b, a, -s).foreach(f))

    // mutating captured operands mid-iteration must not affect the range
    locally {
      val buf = collection.mutable.ListBuffer.empty[Int]
      (a to b by s).foreach { x => buf += x; b = 0; s = 1 }
      assert(buf.toList == List(3, 6, 9, 12, 15), buf.toList)
      b = 17; s = 3
    }

    // function values that are not literals
    locally {
      val buf = collection.mutable.ListBuffer.empty[Int]
      val g: Int => Unit = buf += _
      (1 to 3).foreach(g)
      assert(buf.toList == List(1, 2, 3), buf.toList)
    }

    // per-iteration capture: each closure must see its own element
    locally {
      var fs = List.empty[() => Int]
      (1 to 3).foreach(x => fs ::= (() => x))
      assert(fs.map(_()) == List(3, 2, 1), fs.map(_()))
    }

    // for-comprehensions desugar to foreach
    locally {
      val buf = collection.mutable.ListBuffer.empty[Int]
      for x <- 1 to 5 do buf += x
      for x <- 5 until 0 by -2 do buf += x
      assert(buf.toList == List(1, 2, 3, 4, 5, 5, 3, 1), buf.toList)
    }

    // evaluation order: start, end, step, then the function argument
    locally {
      val order = collection.mutable.ListBuffer.empty[String]
      def start() = { order += "start"; 1 }
      def end() = { order += "end"; 3 }
      def step() = { order += "step"; 1 }
      def fn() = { order += "fn"; (x: Int) => order += x.toString }
      Range(start(), end(), step()).foreach(fn())
      assert(order.toList == List("start", "end", "step", "fn", "1", "2"), order.toList)
    }

    // a zero step must throw eagerly, like the Range constructor, even when empty
    def mustThrow(op: => Unit): Unit =
      try { op; assert(false, "expected IllegalArgumentException") }
      catch case e: IllegalArgumentException => assert(e.getMessage == "step cannot be 0.", e.getMessage)
    val zero = 0
    mustThrow((1 to 10 by zero).foreach(_ => ()))
    mustThrow((1 to 0 by zero).foreach(_ => ()))
    mustThrow(Range(1, 10, zero).foreach(_ => ()))
    mustThrow((1 to 10 by 0).foreach(_ => ()))
    mustThrow(Range.inclusive(1, 0, 0).foreach(_ => ()))
end Test
