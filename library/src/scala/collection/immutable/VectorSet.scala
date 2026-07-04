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
import scala.collection.mutable.Builder

/** This class implements immutable sets using a vector/map-based data
 *  structure, which preserves insertion order.
 *
 *  Unlike `ListSet`, `VectorSet` has amortized effectively constant lookup,
 *  inclusion and exclusion at the expense of using extra memory, which makes
 *  it suitable beyond a small number of elements.
 *
 *  Adding an element that is already present keeps its original position;
 *  removing and re-adding an element moves it to the end.
 *
 *  @tparam A the type of the elements contained in this vector set.
 *
 *  @define coll immutable vector set
 *  @define Coll `immutable.VectorSet`
 */
final class VectorSet[A] private (private val underlying: VectorMap[A, Unit])
  extends AbstractSet[A]
    with SeqSet[A]
    with StrictOptimizedSetOps[A, VectorSet, VectorSet[A]]
    with IterableFactoryDefaults[A, VectorSet]
    with DefaultSerializable {

  override protected def className: String = "VectorSet"

  override def size: Int = underlying.size

  override def knownSize: Int = underlying.knownSize

  override def isEmpty: Boolean = underlying.isEmpty

  def contains(elem: A): Boolean = underlying.contains(elem)

  def incl(elem: A): VectorSet[A] =
    if (underlying.contains(elem)) this
    else new VectorSet(underlying.updated(elem, ()))

  def excl(elem: A): VectorSet[A] = {
    val newUnderlying = underlying.removed(elem)
    if (newUnderlying eq underlying) this
    else new VectorSet(newUnderlying)
  }

  def iterator: Iterator[A] = underlying.keysIterator

  override def head: A = underlying.head._1

  override def last: A = underlying.last._1

  override def tail: VectorSet[A] = new VectorSet(underlying.tail)

  override def init: VectorSet[A] = new VectorSet(underlying.init)

  override def iterableFactory: IterableFactory[VectorSet] = VectorSet
}

object VectorSet extends IterableFactory[VectorSet] {

  private val EmptySet: VectorSet[Nothing] =
    new VectorSet[Nothing](VectorMap.empty[Nothing, Unit])

  def empty[A]: VectorSet[A] = EmptySet.asInstanceOf[VectorSet[A]]

  def from[A](it: collection.IterableOnce[A]^): VectorSet[A] =
    it match {
      case vs: VectorSet[A @unchecked] => vs
      case _ => (newBuilder[A] ++= it).result()
    }

  def newBuilder[A]: Builder[A, VectorSet[A]] = new Builder[A, VectorSet[A]] {
    private val mapBuilder = new VectorMapBuilder[A, Unit]

    override def clear(): Unit = mapBuilder.clear()

    override def result(): VectorSet[A] = {
      val m = mapBuilder.result()
      if (m.isEmpty) empty else new VectorSet(m)
    }

    def addOne(elem: A): this.type = {
      mapBuilder.addOne(elem, ())
      this
    }
  }
}
