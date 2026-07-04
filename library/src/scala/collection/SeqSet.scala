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

package scala.collection

import scala.language.`2.13`
import language.experimental.captureChecking

import scala.annotation.nowarn

/**
  * A generic trait for ordered sets. Concrete classes have to provide
  * functionality for the abstract methods in `SeqSet`.
  *
  * Note that when checking for equality [[SeqSet]] does not take into account
  * ordering.
  *
  * @tparam A the type of the elements contained in this ordered set.
  * @define coll immutable seq set
  * @define Coll `immutable.SeqSet`
  */
trait SeqSet[A] extends Set[A]
  with SetOps[A, SeqSet, SeqSet[A]]
  with IterableFactoryDefaults[A, SeqSet] {
  @nowarn("""cat=deprecation&origin=scala\.collection\.Iterable\.stringPrefix""")
  override protected def stringPrefix: String = "SeqSet"

  override def iterableFactory: IterableFactory[SeqSet] = SeqSet
}

object SeqSet extends IterableFactory.Delegate[immutable.SeqSet](immutable.SeqSet)
