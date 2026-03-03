package dotty.tools.dotc.util

import scala.util.{Try, Failure}
import scala.collection.mutable.ArrayBuffer

/** Stub concurrent utilities for Scala.js — runs everything synchronously. */
object concurrent:

  class NoCompletion extends RuntimeException

  class Future[T](exec: Executor[T]):
    private var result: Option[Try[T]] = None
    def force: Try[T] =
      exec.runAll()
      result.getOrElse(Failure(NoCompletion()))
    def complete(r: Try[T]): Unit =
      result = Some(r)
  end Future

  class Executor[T]:
    private type WorkItem = (Future[T], () => T)
    private var allScheduled = false
    private val pending = new ArrayBuffer[WorkItem]

    def schedule(op: () => T): Future[T] =
      assert(!allScheduled)
      val f = Future[T](this)
      pending += ((f, op))
      f

    def close(): Unit =
      allScheduled = true

    def start(): Unit = () // no-op on JS
    def isAlive(): Boolean = false

    private[concurrent] def runAll(): Unit =
      while pending.nonEmpty do
        val (f, op) = pending.head
        pending.dropInPlace(1)
        f.complete(Try(op()))
  end Executor
end concurrent
