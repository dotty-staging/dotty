/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package collection
package immutable

import scala.language.`2.13`
import language.experimental.captureChecking

import scala.collection.generic.DefaultSerializable
import scala.collection.mutable.{Builder, LinkedHashSet}

/** This class implements immutable sets using a vector-backed data
 *  structure, which preserves insertion order.
 *
 *  Unlike `ListSet`, `VectorSet` has amortized effectively constant lookup
 *  and inclusion at the expense of using extra memory. Exclusion is linear in
 *  the number of elements, which still makes it suitable beyond a small
 *  number of elements.
 *
 *  Adding an element that is already present keeps its original position;
 *  removing and re-adding an element moves it to the end.
 *
 *  @tparam A the type of the elements contained in this vector set.
 *
 *  @define coll immutable vector set
 *  @define Coll `immutable.VectorSet`
 */
final class VectorSet[A] private (
    private val elements: Vector[A],
    private val membership: Set[A])
  extends AbstractSet[A]
    with SeqSet[A]
    with StrictOptimizedSetOps[A, VectorSet, VectorSet[A]]
    with IterableFactoryDefaults[A, VectorSet]
    with DefaultSerializable {

  override protected def className: String = "VectorSet"

  override def size: Int = elements.size

  override def knownSize: Int = size

  override def isEmpty: Boolean = elements.isEmpty

  def contains(elem: A): Boolean = membership.contains(elem)

  def incl(elem: A): VectorSet[A] =
    if (membership.contains(elem)) this
    else new VectorSet(elements :+ elem, membership + elem)

  def excl(elem: A): VectorSet[A] = {
    if (!membership.contains(elem)) this
    else new VectorSet(elements.filterNot(_ == elem), membership - elem)
  }

  def iterator: Iterator[A] = elements.iterator

  override def head: A = elements.head

  override def last: A = elements.last

  override def tail: VectorSet[A] = {
    if (isEmpty) throw new UnsupportedOperationException("empty.tail")
    new VectorSet(elements.tail, membership - elements.head)
  }

  override def init: VectorSet[A] = {
    if (isEmpty) throw new UnsupportedOperationException("empty.init")
    new VectorSet(elements.init, membership - elements.last)
  }

  override def iterableFactory: IterableFactory[VectorSet] = VectorSet
}

object VectorSet extends IterableFactory[VectorSet] {

  private val EmptySet: VectorSet[Nothing] =
    new VectorSet[Nothing](Vector.empty, Set.empty)

  def empty[A]: VectorSet[A] = EmptySet.asInstanceOf[VectorSet[A]]

  def from[A](it: collection.IterableOnce[A]^): VectorSet[A] =
    it match {
      case vs: VectorSet[A @unchecked] => vs
      case it: Iterable[?] if it.isEmpty => empty[A]
      case _ => (newBuilder[A] ++= it).result()
    }

  def newBuilder[A]: Builder[A, VectorSet[A]] = new Builder[A, VectorSet[A]] {
    private val elems = LinkedHashSet.empty[A]

    override def clear(): Unit = elems.clear()

    override def result(): VectorSet[A] = {
      if (elems.isEmpty) empty
      else new VectorSet(elems.toVector, elems.toSet)
    }

    def addOne(elem: A): this.type = {
      elems += elem
      this
    }
  }
}
