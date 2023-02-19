package concurrent

import scala.collection.mutable, mutable.ListBuffer
import fiberRuntime.boundary
import scala.compiletime.uninitialized
import scala.util.{Try, Success, Failure}
import scala.annotation.unchecked.uncheckedVariance
import java.util.concurrent.CancellationException

/** A cancellable future that can suspend waiting for other asynchronous sources
 */
trait Future[+T] extends Async.OriginalSource[Try[T]], Cancellable:

  /** Wait for this future to be completed, return its value in case of success,
   *  or rethrow exception in case of failure.
   */
  def value(using async: Async): T

  /** Eventually stop computation of this future and fail with
   *  a `Cancellation` exception. Also cancel all children.
   */
  def cancel(): Unit

  /** If this future has not yet completed, add `child` so that it will
   *  be cancelled together with this future in case the future is cancelled.
   */
  def addChild(child: Cancellable): Unit

object Future:

  /**  A future that is completed explicitly by calling its
   *  `complete` method. There are two public implementations
   *
   *   - RunnableFuture: Completion is done by running a block of code
   *   - Promise.future: Completion is done by external request.
   */
  private class CoreFuture[+T] extends Future[T]:

    @volatile protected var hasCompleted: Boolean = false
    private var result: Try[T] = uninitialized // guaranteed to be set if hasCompleted = true
    private val waiting: mutable.Set[Try[T] => Boolean] = mutable.Set()
    private val children: mutable.Set[Cancellable] = mutable.Set()

    private def extract[T](s: mutable.Set[T]): List[T] = synchronized:
      val xs = s.toList
      s.clear()
      xs

    // Async.Source method implementations

    def poll(k: Async.Listener[Try[T]]): Boolean =
      hasCompleted && k(result)

    def addListener(k: Async.Listener[Try[T]]): Unit = synchronized:
      waiting += k

    def dropListener(k: Async.Listener[Try[T]]): Unit = synchronized:
      waiting -= k

    // Cancellable method implementations

    def cancel(): Unit =
      val othersToCancel = synchronized:
        if hasCompleted then Nil
        else
          result = Failure(new CancellationException())
          hasCompleted = true
          extract(children)
      othersToCancel.foreach(_.cancel())

    def addChild(child: Cancellable): Unit = synchronized:
      if !hasCompleted then children += this

    // Future method implementations

    def value(using async: Async): T =
      async.await(this).get

    /** Complete future with result. If future was cancelled in the meantime,
     *  return a CancellationException failure instead.
     *  Note: @uncheckedVariance is safe here since `complete` is called from
     *  only two places:
     *   - from the initializer of RunnableFuture, where we are sure that `T`
     *     is exactly the type with which the future was created, and
     *   - from Promise.complete, where we are sure the type `T` is exactly
     *     the type with which the future was created since `Promise` is invariant.
     */
    private[Future] def complete(result: Try[T] @uncheckedVariance): Unit =
      if !hasCompleted then
        this.result = result
        hasCompleted = true
      for listener <- extract(waiting) do listener(result)

  end CoreFuture

  /** A future that is completed by evaluating `body` as a separate
   *  asynchronous operation in the given `scheduler`
   */
  private class RunnableFuture[+T](body: Async ?=> T)(using scheduler: Scheduler)
  extends CoreFuture[T]:

    /** a handler for Async */
    private def async(body: Async ?=> Unit): Unit =
      boundary [Unit]:
        given Async = new Async.Impl(this, scheduler):
          def checkCancellation() =
            if hasCompleted then throw new CancellationException()
        body
    end async

    scheduler.schedule: () =>
      async(complete(Try(body)))

  end RunnableFuture

  /** Create a future that asynchronously executes `body` that defines
   *  its result value in a Try or returns failure if an exception was thrown.
   *  If the future is created in an Async context, it is added to the
   *  children of that context's root.
   */
  def apply[T](body: Async ?=> T)(using ac: Async.Config): Future[T] =
    val f = RunnableFuture(body)(using ac.scheduler)
    ac.root.addChild(f)
    f

  extension [T1](f1: Future[T1])

    /** Parallel composition of two futures.
     *  If both futures succeed, succeed with their values in a pair. Otherwise,
     *  fail with the failure that was returned first and cancel the other.
     */
    def zip[T2](f2: Future[T2])(using Async.Config): Future[(T1, T2)] = Future:
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1))  => (x1, f2.value)
        case Right(Success(x2)) => (f1.value, x2)
        case Left(Failure(ex))  => f2.cancel(); throw ex
        case Right(Failure(ex)) => f1.cancel(); throw ex

    /** Alternative parallel composition of this task with `other` task.
     *  If either task succeeds, succeed with the success that was returned first
     *  and cancel the other. Otherwise, fail with the failure that was returned last.
     */
    def alt[T2 >: T1](f2: Future[T2], name: String = "alt")(using Async.Config): Future[T2] = Future:
      boundary.setName(name)
      Async.await(Async.either(f1, f2)) match
        case Left(Success(x1))    => f2.cancel(); x1
        case Right(Success(x2))   => f1.cancel(); x2
        case Left(_: Failure[?])  => f2.value
        case Right(_: Failure[?]) => f1.value

  end extension

  // TODO: efficient n-ary versions of the last two operations

  /** A promise defines a future that is be completed via the
   *  promise's `complete` method.
   */
  class Promise[T]:
    private val myFuture = CoreFuture[T]()

    /** The future defined by this promise */
    val future: Future[T] = myFuture

    /** Define the result value of `future`. However, if `future` was
     *  cancelled in the meantime complete with a `CancellationException`
     *  failure instead.
     */
    def complete(result: Try[T]): Unit = myFuture.complete(result)

  end Promise
end Future

/** A task is a template that can be turned into a runnable future
 *  Composing tasks can be referentially transparent.
 */
class Task[+T](val body: Async ?=> T):

  /** Start a future computed from the `body` of this task */
  def run(using Async.Config) = Future(body)

end Task

def add(x: Future[Int], xs: List[Future[Int]])(using Scheduler): Future[Int] =
  val b = x.zip:
    Future:
      xs.headOption.toString

  val _: Future[(Int, String)] = b

  val c = x.alt:
    Future:
      b.value._1
  val _: Future[Int] = c

  Future:
    val f1 = Future:
      x.value * 2
    x.value + xs.map(_.value).sum

end add

def Main(x: Future[Int], xs: List[Future[Int]])(using Scheduler): Int =
  Async.blocking(add(x, xs).value)

