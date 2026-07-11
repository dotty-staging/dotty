package scala.runtime

import language.experimental.captureChecking

object Scala3RunTime:

  // Called by inline def assert's. Extracted to minimize the bytecode size at call site.

  def assertFailed(message: Any): Nothing =
    throw new java.lang.AssertionError("assertion failed: " + message)

  def assertFailed(): Nothing =
    throw new java.lang.AssertionError("assertion failed")

  /** Called by the inline extension def `nn`.
   *
   *  Extracted to minimize the bytecode size at call site.
   */
  @deprecated("use Predef.nn instead", "3.2")
  def nn[T](x: T | Null): x.type & T =
    val isNull = x == null
    if isNull then nnFail()
    else x.asInstanceOf[x.type & T]

  /** Called by the inline extension def `nn`.
   *
   *  Extracted to minimize the bytecode size at call site.
   *
   *  @throws NullPointerException always, indicating that a null value was encountered where non-null was expected
   */
  def nnFail(): Nothing =
    throw new NullPointerException("tried to cast away nullability, but value is null")

  /** The last element of a non-empty range with the given parameters, i.e.
   *  `Range#lastElement`.
   *
   *  Called by the compiler's `rangeForeachOpt` phase when it inlines
   *  `Range#foreach` and the step is not statically known. Extracted to
   *  minimize the bytecode size at call site; the body is the same
   *  `Range.lastElementOf` that the `Range` constructor inlines.
   *
   *  precondition: `step != 0` and the range is non-empty.
   */
  def rangeLastElement(start: Int, end: Int, step: Int, isInclusive: Boolean): Int =
    scala.collection.immutable.Range.lastElementOf(start, end, step, isInclusive)
end Scala3RunTime
