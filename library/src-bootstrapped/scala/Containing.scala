package scala

import scala.language.experimental.{modularity, clauseInterleaving}
import scala.annotation.experimental
import scala.TmpPredef.TypeClass


/** TODO
 *   - documentation
 *   - move into Predef (we get errors if it is not in src-bootstrapped for now)
 * */
object TmpPredef:

  extension [T](x: T) @experimental def as [U](using Conversion[T, U]): U = x.convert

  @experimental trait TypeClass:
    type Self

end TmpPredef


/** A value together with an evidence of its type conforming to some type class.
 *
 *  The `witness` is instance of the `Concept` for `Value`.
 *  It is included in the implicit scope of expressions with type `this.Value`,
 *  the only source of which is `this.value`.
 *
 *  Any selection of the form
 *     `qual.name`
 *  where `qual` derives from `Containing` and `name` is not a member of `qual` will attempt
 *     `qual.value.name`
 *
 *  @tparam Concept The type class representing the interface required for the wrapped value
 *
 */
@experimental sealed trait Containing[Concept <: TypeClass]:
  /** The type of the contained value. */
  type Value : Concept as witness
  /** The contained value. */
  val value: Value

object Containing:

  /** A `Containing[C]` where the contained value is known to have type `V`.
   *
   *  Keeping the member `type Value` abstract by using equal bounds instead of `type Value = V`,
   *  preserves it's existential nature and the achor to the `witness` that it models `C`.
   * */
  type Precisely[C <: TypeClass, V] = Containing[C] { type Value >: V <: V }

  /** Wrapes a value of type `V` into a `Containing[C]` provided a witness that `V is C`.
   *
   *  The first type parameter list is `[C <: TypeClass]` to maintain the same signature as the trait.
   *  But the witness is not in the first term parameter list,
   *  to allow constraining its `type Self` with the one of the value being wrapped.
   */
  def apply[C <: TypeClass](v: Any)[V >: v.type : C] = new Precisely[C, V]:
    val value: Value = v

  /** An implicit constructor for `Containing.Precisely[C, V]` from `V`. */
  given constructor[C <: TypeClass, V : C]: Conversion[V, Precisely[C, V]] = apply

end Containing
