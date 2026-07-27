package scala.runtime

/** Runtime support for the experimental unboxed-Option ABI (`-Yunboxed-options`).
 *
 *  Under that flag, every method with `Option` in its (erased) signature gets a
 *  companion `<name>$unboxed` entry point in which Option-typed parameters and
 *  results are passed as plain references in a "slot" typed with the erasure of
 *  the Option's type argument, so the JIT sees precise types. Two encodings
 *  exist, chosen statically per position:
 *
 *  '''Precise slots''' (slot type is not `Object`), converted by
 *  [[boxPrecise]]/[[unboxPrecise]]:
 *
 *    - `None`      is represented by `null`
 *    - `Some(v)`   is represented by `v` directly
 *    - `Some(null)` cannot be represented: a slot of type `S` has one spare
 *      value (`null`) but the domain needs both `None` and `Some(null)`.
 *      [[unboxPrecise]] throws `IllegalArgumentException` on it. Compiling
 *      with `-Yexplicit-nulls` makes this statically unreachable for
 *      non-nullable payload types; nullable payloads use generic slots.
 *
 *  '''Generic slots''' (slot type `Object`, e.g. from `Option[T]` with an
 *  unbounded `T`), converted by [[box]]/[[unbox]] — a total encoding:
 *
 *    - `None`      is represented by the `None` object itself
 *    - `Some(v)`   is represented by `v` directly, unless `v` is `null`,
 *                  `None`, or itself a [[UnboxedOptions.Wrapped]], in which
 *                  case it is represented by `new Wrapped(v)`
 *    - a `null` Option reference is passed through as `null`
 *
 *  In the common cases (`None`, `Some` of an ordinary value) all conversions
 *  are allocation-free in the unbox direction and allocate at most one `Some`
 *  cell in the box direction.
 */
object UnboxedOptions:

  /** Wraps payloads of generic slots whose direct representation would be
   *  ambiguous: `null`, the `None` object, or another `Wrapped` cell.
   */
  final class Wrapped(val value: AnyRef)

  /** Convert a generic-slot representation back to a standard `Option`. */
  def box(r: AnyRef): Option[AnyRef] =
    if r eq null then null.asInstanceOf[Option[AnyRef]]
    else if r eq None then None
    else r match
      case w: Wrapped => Some(w.value)
      case _ => Some(r)

  /** Convert a standard `Option` to its generic-slot representation. */
  def unbox(o: Option[AnyRef]): AnyRef =
    if o eq null then null.asInstanceOf[AnyRef]
    else if o eq None then None
    else
      val v = o.get
      if (v eq null) || (v eq None) || v.isInstanceOf[Wrapped] then new Wrapped(v)
      else v

  /** Convert a precise-slot representation back to a standard `Option`. */
  def boxPrecise(r: AnyRef): Option[AnyRef] =
    if r eq null then None else Some(r)

  /** Convert a standard `Option` to its precise-slot representation.
   *  The caller casts the result to the slot type.
   */
  def unboxPrecise(o: Option[AnyRef]): AnyRef =
    if o.isEmpty then null.asInstanceOf[AnyRef]
    else
      val v = o.get
      if v eq null then
        throw new IllegalArgumentException(
          "Some(null) cannot cross an unboxed-Option ABI boundary with a specialized slot type")
      v
end UnboxedOptions
