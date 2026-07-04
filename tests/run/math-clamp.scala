import scala.math.clamp

def throwsIAE(op: => Any): Boolean =
  try { op; false } catch { case _: IllegalArgumentException => true }

@main def Test: Unit =
  // (Long, Int, Int): Int — also what plain Int arguments resolve to
  val i: Int = clamp(5, 0, 10)
  assert(i == 5)
  assert(clamp(-5, 0, 10) == 0)
  assert(clamp(15, 0, 10) == 10)
  // clamping a Long into an Int range without overflow checks
  assert(clamp(Long.MaxValue, 0, 10) == 10)
  assert(clamp(Long.MinValue, -3, 3) == -3)
  assert(clamp(Int.MaxValue.toLong + 1, Int.MinValue, Int.MaxValue) == Int.MaxValue)
  assert(throwsIAE(clamp(0, 10, -10)))

  // (Long, Long, Long): Long
  val l: Long = clamp(5L, 0L, 10L)
  assert(l == 5L)
  assert(clamp(Long.MinValue, -10L, 10L) == -10L)
  assert(clamp(Long.MaxValue, -10L, 10L) == 10L)
  assert(throwsIAE(clamp(0L, 1L, 0L)))

  // (Float, Float, Float): Float
  assert(clamp(0.5f, 0.0f, 1.0f) == 0.5f)
  assert(clamp(-1.5f, 0.0f, 1.0f) == 0.0f)
  assert(clamp(2.5f, 0.0f, 1.0f) == 1.0f)
  assert(clamp(Float.NaN, 0.0f, 1.0f).isNaN) // NaN value passes through
  assert(1.0f / clamp(-0.0f, 0.0f, 1.0f) == Float.PositiveInfinity) // -0.0 resolved against +0.0 bound
  assert(throwsIAE(clamp(0.5f, 1.0f, 0.0f)))
  assert(throwsIAE(clamp(0.5f, Float.NaN, 1.0f))) // NaN bound rejected
  assert(throwsIAE(clamp(0.5f, 0.0f, Float.NaN)))

  // (Double, Double, Double): Double
  assert(clamp(0.5, 0.0, 1.0) == 0.5)
  assert(clamp(-1.5, 0.0, 1.0) == 0.0)
  assert(clamp(2.5, 0.0, 1.0) == 1.0)
  assert(clamp(Double.NaN, 0.0, 1.0).isNaN)
  assert(1.0 / clamp(-0.0, 0.0, 1.0) == Double.PositiveInfinity)
  assert(throwsIAE(clamp(0.5, 1.0, 0.0)))
  assert(throwsIAE(clamp(0.5, Double.NaN, 1.0)))
  assert(throwsIAE(clamp(0.5, 0.0, Double.NaN)))

  // generic Ordering-based clamp
  assert(clamp("m", "a", "z") == "m")
  assert(clamp("A", "a", "z") == "a")
  assert(clamp("~", "a", "z") == "z")
  assert(clamp(BigInt(300), BigInt(0), BigInt(255)) == BigInt(255))
  assert(throwsIAE(clamp("m", "z", "a")))
