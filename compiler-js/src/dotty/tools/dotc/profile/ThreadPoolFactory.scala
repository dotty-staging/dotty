package dotty.tools.dotc.profile

import java.util.concurrent.ThreadPoolExecutor.AbortPolicy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.Contexts.*

sealed trait ThreadPoolFactory {

  def newUnboundedQueueFixedThreadPool(
    nThreads: Int,
    shortId: String,
    priority : Int = Thread.NORM_PRIORITY) : ThreadPoolExecutor

  def newBoundedQueueFixedThreadPool(
    nThreads: Int,
    maxQueueSize: Int,
    rejectHandler: RejectedExecutionHandler,
    shortId: String,
    priority : Int = Thread.NORM_PRIORITY) : ThreadPoolExecutor
}

object ThreadPoolFactory {
  def apply(phase: Phase)(using Context): ThreadPoolFactory = new BasicThreadPoolFactory(phase)

  private final class BasicThreadPoolFactory(phase: Phase) extends ThreadPoolFactory {
    override def newUnboundedQueueFixedThreadPool(nThreads: Int, shortId: String, priority: Int): ThreadPoolExecutor = {
      new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable])
    }

    override def newBoundedQueueFixedThreadPool(nThreads: Int, maxQueueSize: Int, rejectHandler: RejectedExecutionHandler, shortId: String, priority: Int): ThreadPoolExecutor = {
      new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue[Runnable](maxQueueSize), rejectHandler)
    }
  }
}
