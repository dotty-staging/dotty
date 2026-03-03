package dotty.tools
package dotc
package sbt

import scala.collection.mutable.ListBuffer
import xsbti.api

/** Stub ThunkHolder for Scala.js */
private[sbt] trait ThunkHolder {
  private val thunks = new ListBuffer[api.Lazy[?]]

  @scala.annotation.tailrec protected final def forceThunks(): Unit = if (!thunks.isEmpty) {
    val toForce = thunks.toList
    thunks.clear()
    toForce.foreach(_.get())
    forceThunks()
  }

  def lzy[T <: AnyRef](t: => T): api.Lazy[T] = {
    val l: api.Lazy[T] = new api.Lazy[T] {
      private lazy val value: T = t
      def get(): T = value
    }
    thunks += l
    l
  }
}
