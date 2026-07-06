package scala
package compiletime

import language.experimental.captureChecking

import scala.annotation.experimental
import scala.collection.immutable
import scala.collection.mutable
import scala.reflect.ClassTag

/** A type class that makes a type expressible with a collection literal
 *  `[x1, ..., xn]` under `import scala.language.experimental.collectionLiterals`.
 *
 *  If a collection literal appears with expected type `C`, and a given
 *  instance of `ExpressibleAsCollectionLiteral[C]` can be summoned, the
 *  literal is elaborated to `instance.fromLiteral(x1, ..., xn)`, and the
 *  literal's elements are type-checked with expected type `instance.Elem`.
 *  With no usable expected type, a collection literal is a
 *  `scala.collection.immutable.Seq`, as fixed by the language specification;
 *  no instance is consulted in that case.
 *
 *  Unlike the version proposed in the Pre-SIP discussion, `Coll` is invariant:
 *  a covariant parameter would make the instance for `Vector[T]` also eligible
 *  at expected type `Seq[T]`, so every `Seq`-targeted literal would be an
 *  ambiguity error.
 */
@experimental
trait ExpressibleAsCollectionLiteral[Coll]:
  /** The element type of the created collection */
  type Elem

  /** The inline method that creates the collection */
  inline def fromLiteral(inline xs: Elem*): Coll

@experimental
object ExpressibleAsCollectionLiteral:

  /** Instances are concrete final classes rather than anonymous instances of
   *  the trait so that `fromLiteral` calls resolve to a concrete inline method.
   */
  final class SeqFromLiteral[T] extends ExpressibleAsCollectionLiteral[Seq[T]]:
    type Elem = T
    inline def fromLiteral(inline xs: T*): Seq[T] = Seq(xs*)

  final class ListFromLiteral[T] extends ExpressibleAsCollectionLiteral[List[T]]:
    type Elem = T
    inline def fromLiteral(inline xs: T*): List[T] = List(xs*)

  final class VectorFromLiteral[T] extends ExpressibleAsCollectionLiteral[Vector[T]]:
    type Elem = T
    inline def fromLiteral(inline xs: T*): Vector[T] = Vector(xs*)

  final class SetFromLiteral[T] extends ExpressibleAsCollectionLiteral[Set[T]]:
    type Elem = T
    inline def fromLiteral(inline xs: T*): Set[T] = Set(xs*)

  final class MapFromLiteral[K, V] extends ExpressibleAsCollectionLiteral[Map[K, V]]:
    type Elem = (K, V)
    inline def fromLiteral(inline xs: (K, V)*): Map[K, V] = Map(xs*)

  final class IArrayFromLiteral[T: ClassTag] extends ExpressibleAsCollectionLiteral[IArray[T]]:
    type Elem = T
    inline def fromLiteral(inline xs: T*): IArray[T] = IArray(xs*)

  given seqFromLiteral: [T] => SeqFromLiteral[T] = SeqFromLiteral[T]()
  given listFromLiteral: [T] => ListFromLiteral[T] = ListFromLiteral[T]()
  given vectorFromLiteral: [T] => VectorFromLiteral[T] = VectorFromLiteral[T]()
  given setFromLiteral: [T] => SetFromLiteral[T] = SetFromLiteral[T]()
  given mapFromLiteral: [K, V] => MapFromLiteral[K, V] = MapFromLiteral[K, V]()
  given iarrayFromLiteral: [T: ClassTag] => IArrayFromLiteral[T] = IArrayFromLiteral[T]()

  /** Instances for mutable collections. These are deliberately not part of the
   *  implicit scope: a literal can only satisfy a mutable expected type after
   *  an explicit `import ExpressibleAsCollectionLiteral.mutableLiterals.given`.
   */
  object mutableLiterals:

    final class ArrayFromLiteral[T: ClassTag] extends ExpressibleAsCollectionLiteral[Array[T]]:
      type Elem = T
      inline def fromLiteral(inline xs: T*): Array[T] = Array(xs*)

    final class MutableSeqFromLiteral[T] extends ExpressibleAsCollectionLiteral[mutable.Seq[T]]:
      type Elem = T
      inline def fromLiteral(inline xs: T*): mutable.Seq[T] = mutable.Seq(xs*)

    final class ArrayBufferFromLiteral[T] extends ExpressibleAsCollectionLiteral[mutable.ArrayBuffer[T]]:
      type Elem = T
      inline def fromLiteral(inline xs: T*): mutable.ArrayBuffer[T] = mutable.ArrayBuffer(xs*)

    final class MutableSetFromLiteral[T] extends ExpressibleAsCollectionLiteral[mutable.Set[T]]:
      type Elem = T
      inline def fromLiteral(inline xs: T*): mutable.Set[T] = mutable.Set(xs*)

    final class MutableMapFromLiteral[K, V] extends ExpressibleAsCollectionLiteral[mutable.Map[K, V]]:
      type Elem = (K, V)
      inline def fromLiteral(inline xs: (K, V)*): mutable.Map[K, V] = mutable.Map(xs*)

    given arrayFromLiteral: [T: ClassTag] => ArrayFromLiteral[T] = ArrayFromLiteral[T]()
    given mutableSeqFromLiteral: [T] => MutableSeqFromLiteral[T] = MutableSeqFromLiteral[T]()
    given arrayBufferFromLiteral: [T] => ArrayBufferFromLiteral[T] = ArrayBufferFromLiteral[T]()
    given mutableSetFromLiteral: [T] => MutableSetFromLiteral[T] = MutableSetFromLiteral[T]()
    given mutableMapFromLiteral: [K, V] => MutableMapFromLiteral[K, V] = MutableMapFromLiteral[K, V]()
  end mutableLiterals
end ExpressibleAsCollectionLiteral
