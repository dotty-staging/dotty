# SLC Proposal: `scala.math.clamp`

**Status:** draft implementation, approved HIGH priority in STA review (item 4.14)
**Source:** https://github.com/scala/scala-library-next/issues/55

## Motivation

Constraining a value to a closed interval is a ubiquitous micro-operation — pixel
and colour channels, indices, scroll offsets, retry backoffs, rate limits, audio
samples. Today it is written as `math.min(math.max(value, lower), upper)`, which
is easy to get wrong (swapping `min`/`max` silently pins the value to a bound) and
obscures intent. Java 21 added `Math.clamp` for exactly this reason; Scala should
offer the same convenience regardless of the JDK the program runs on.

Primitive overloads avoid boxing on the hot paths where clamping typically
appears, and an `Ordering`-based overload covers all other ordered types.

## Proposed API

Added to the `scala.math` package object:

```scala
def clamp(value: Long, lower: Int, upper: Int): Int
def clamp(value: Long, lower: Long, upper: Long): Long
def clamp(value: Float, lower: Float, upper: Float): Float
def clamp(value: Double, lower: Double, upper: Double): Double
def clamp[T](value: T, lower: T, upper: T)(using Ordering[T]): T
```

## Semantics

- values below `lower` return `lower`; values above `upper` return `upper`;
  values inside the interval are returned unchanged
- `IllegalArgumentException` when `lower > upper` (matching `java.lang.Math.clamp`)
- the `Int`-returning overload takes a `Long` value with `Int` bounds, so a
  `Long` can be clamped into an `Int` range with no separate overflow check —
  this also makes plain `clamp(5, 0, 10)` resolve to it and return `Int`
- floating point: a NaN *value* passes through unchanged; a NaN *bound* throws
  `IllegalArgumentException`; `-0.0` vs `+0.0` resolves as by `math.min`/`math.max`
  (all matching Java 21 `Math.clamp`)

## Design notes

- Implemented by hand rather than delegating to `java.lang.Math.clamp`, since the
  library supports JDKs older than 21. Semantics deliberately match Java's so a
  future delegation is a no-op.
- Documented under the existing `minmax` scaladoc group next to `max`/`min`.
- No `Int`-only overload `(Int, Int, Int): Int` is needed: the `(Long, Int, Int)`
  overload subsumes it via numeric widening while staying unambiguous (it is the
  most specific applicable alternative), exactly as in Java.

## Compatibility

Pure addition to the `scala.math` package object; one MiMa
`ForwardsBreakingChanges` filter entry (`scala.math.package.clamp`).

## Tests

`tests/run/math-clamp.scala` exercises every overload: interior/boundary/outside
values, Long-into-Int narrowing, NaN value passthrough, NaN bound rejection,
signed-zero resolution, and `lower > upper` rejection.
