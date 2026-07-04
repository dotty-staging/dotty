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

import scala.collection.mutable.Builder

/** A base trait for ordered, immutable sets.
 *
 *  Note that the [[equals]] method for [[SeqSet]] compares elements without
 *  regard to ordering.
 *
 *  All behavior is defined in terms of the abstract methods in `SeqSet`.
 *  It is sufficient for concrete subclasses to implement those methods.
 *  Methods that return a new set, in particular [[incl]] and [[excl]], must
 *  preserve ordering.
 *
 *  @tparam A the type of the elements contained in this ordered set.
 *
 *  @define coll immutable seq set
 *  @define Coll `immutable.SeqSet`
 */
trait SeqSet[A]
  extends Set[A]
    with collection.SeqSet[A]
    with SetOps[A, SeqSet, SeqSet[A]]
    with IterableFactoryDefaults[A, SeqSet] {
  override def iterableFactory: IterableFactory[SeqSet] = SeqSet
}

object SeqSet extends IterableFactory[SeqSet] {
  def empty[A]: SeqSet[A] = VectorSet.empty[A]

  def from[A](it: collection.IterableOnce[A]^): SeqSet[A] =
    it match {
      case ss: SeqSet[A @unchecked] => ss
      case _ => VectorSet.from(it)
    }

  def newBuilder[A]: Builder[A, SeqSet[A]] = VectorSet.newBuilder[A]
}
