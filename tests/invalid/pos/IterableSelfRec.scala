// This does not currently work because it mixes higher-kinded types and raw type constructors.
package dotty.collection
package immutable

import annotation.unchecked.uncheckedVariance

trait Collection[T] { self =>
  type ThisCollection <: Collection { type ThisCollection <: self.ThisCollection }
  def companion: CollectionCompanion[ThisCollection]
}

trait Iterable[T] extends Collection[T] { self =>
  type ThisCollection <: Iterable { type ThisCollection <: self.ThisCollection }
  override def companion: IterableCompanion[ThisCollection] = Iterable.asInstanceOf

  def iterator: Iterator[T]
}

trait Seq[T] extends Iterable[T] { self =>
  type ThisCollection <: Seq { type ThisCollection <: self.ThisCollection }
  override def companion: IterableCompanion[ThisCollection] = Seq.asInstanceOf

  def apply(x: Int): T
}

abstract class CollectionCompanion[+CC[X] <: Collection[X] { type ThisCollection <: CC }]

abstract class IterableCompanion[+CC[X] <: Iterable[X] { type ThisCollection <: CC }] extends CollectionCompanion[CC] {
  def fromIterator[T](it: Iterator[T]): CC[T]
  def map[T, U](xs: Iterable[T], f: T => U): CC[U] =
    fromIterator(xs.iterator.map(f))
  def filter[T](xs: Iterable[T], p: T => Boolean): CC[T] =
    fromIterator(xs.iterator.filter(p))
  def flatMap[T, U](xs: Iterable[T], f: T => TraversableOnce[U]): CC[U] =
    fromIterator(xs.iterator.flatMap(f))

  implicit def transformOps[T](xs: CC[T] @uncheckedVariance): TransformOps[CC, T] = ??? // new TransformOps[CC, T](xs)
}

class TransformOps[+CC[X] <: Iterable[X] { type ThisCollection <: CC }, T] (val xs: CC[T]) extends AnyVal {
  def companion[T](xs: CC[T] @uncheckedVariance): IterableCompanion[CC] = xs.companion
  def map[U](f: T => U): CC[U] = companion(xs).map(xs, f)
  def filter(p: T => Boolean): CC[T] = companion(xs).filter(xs, p)
  def flatMap[U](f: T => TraversableOnce[U]): CC[U] = companion(xs).flatMap(xs, f)
}

object Iterable extends IterableCompanion[Iterable] {
  def fromIterator[T](it: Iterator[T]): Iterable[T] = ???
}
object Seq extends IterableCompanion[Seq] {
  def fromIterator[T](it: Iterator[T]): Seq[T] = ???
}

