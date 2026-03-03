package dotty.tools.dotc.profile

import scala.annotation.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Symbols.{Symbol, NoSymbol}
import dotty.tools.dotc.core.Flags
// symExtensions import removed - provided by GenBCode stub on JS
import dotty.tools.io.AbstractFile
import annotation.internal.sharable
import dotty.tools.dotc.core.Periods.InitialRunId

object Profiler {
  def apply()(using Context): Profiler = NoOpProfiler

  final def NoOp: Profiler = NoOpProfiler

  private[profile] val emptySnap: ProfileSnap = ProfileSnap(0, "", 0, 0, 0, 0, 0, 0, 0, 0)
}

case class GcEventData(pool:String, reportTimeNs: Long, gcStartMillis:Long, gcEndMillis:Long, durationMillis: Long, name:String, action:String, cause:String, threads:Long){
  val endNanos = System.nanoTime()
}

case class ProfileSnap(threadId: Long, threadName: String, snapTimeNanos : Long,
                       idleTimeNanos:Long, cpuTimeNanos: Long, userTimeNanos: Long,
                       allocatedBytes:Long, heapBytes:Long,
                       totalClassesLoaded: Long, totalJITCompilationTime: Long) {
  def updateHeap(heapBytes:Long): ProfileSnap =
    copy(heapBytes = heapBytes)
}
case class ProfileRange(start: ProfileSnap, end:ProfileSnap, phase:Phase, purpose:String, taskCount:Int, thread:Thread) {
  def allocatedBytes: Long = end.allocatedBytes - start.allocatedBytes

  def userNs: Long = end.userTimeNanos - start.userTimeNanos

  def cpuNs: Long = end.cpuTimeNanos - start.cpuTimeNanos

  def idleNs: Long = end.idleTimeNanos - start.idleTimeNanos

  def runNs: Long = end.snapTimeNanos - start.snapTimeNanos


  private def toMillis(ns: Long) = ns / 1000000.0D

  private def toMegaBytes(bytes: Long) = bytes / 1000000.0D


  def wallClockTimeMillis: Double = toMillis(end.snapTimeNanos - start.snapTimeNanos)

  def idleTimeMillis: Double = toMillis(end.idleTimeNanos - start.idleTimeNanos)

  def cpuTimeMillis: Double = toMillis(end.cpuTimeNanos - start.cpuTimeNanos)

  def userTimeMillis: Double = toMillis(end.userTimeNanos - start.userTimeNanos)

  def allocatedMB: Double = toMegaBytes(end.allocatedBytes - start.allocatedBytes)

  def retainedHeapMB: Double = toMegaBytes(end.heapBytes - start.heapBytes)
}

private opaque type TracedEventId <: String = String
private object TracedEventId:
  def apply(stringValue: String): TracedEventId = stringValue
  final val Empty: TracedEventId = ""

sealed trait Profiler {

  def finished(): Unit

  inline def onPhase[T](phase: Phase)(inline body: T): T =
    val (event, snapshot) = beforePhase(phase)
    try body
    finally afterPhase(event, phase, snapshot)
  protected final val EmptyPhaseEvent = (TracedEventId.Empty, Profiler.emptySnap)
  protected def beforePhase(phase: Phase): (TracedEventId, ProfileSnap) = EmptyPhaseEvent
  protected def afterPhase(event: TracedEventId, phase: Phase, profileBefore: ProfileSnap): Unit = ()

  inline def onUnit[T](phase: Phase, unit: CompilationUnit)(inline body: T): T =
    val event = beforeUnit(phase, unit)
    try body
    finally afterUnit(event)
  protected def beforeUnit(phase: Phase, unit: CompilationUnit): TracedEventId = TracedEventId.Empty
  protected def afterUnit(event: TracedEventId): Unit = ()

  inline def onTypedDef[T](sym: Symbol)(inline body: T): T =
    val event = beforeTypedDef(sym)
    try body
    finally afterTypedDef(event)
   protected def beforeTypedDef(sym: Symbol): TracedEventId = TracedEventId.Empty
   protected def afterTypedDef(token: TracedEventId): Unit = ()

  inline def onImplicitSearch[T](pt: Type)(inline body: T): T =
    val event = beforeImplicitSearch(pt)
    try body
    finally afterImplicitSearch(event)
  protected def beforeImplicitSearch(pt: Type): TracedEventId  = TracedEventId.Empty
  protected def afterImplicitSearch(event: TracedEventId): Unit = ()

  inline def onInlineCall[T](inlineSym: Symbol)(inline body: T): T =
    val event = beforeInlineCall(inlineSym)
    try body
    finally afterInlineCall(event)
  protected def beforeInlineCall(inlineSym: Symbol): TracedEventId = TracedEventId.Empty
  protected def afterInlineCall(event: TracedEventId): Unit = ()

  inline def onCompletion[T](root: Symbol, associatedFile: => AbstractFile)(inline body: T): T =
    val (event, completionName) = beforeCompletion(root, associatedFile)
    try body
    finally afterCompletion(event, completionName)
  protected final val EmptyCompletionEvent = (TracedEventId.Empty, "")
  protected def beforeCompletion(root: Symbol, associatedFile: => AbstractFile): (TracedEventId, String) = EmptyCompletionEvent
  protected def afterCompletion(event: TracedEventId, completionName: String): Unit = ()
}
private [profile] object NoOpProfiler extends Profiler {
  override def finished(): Unit = ()
}

enum EventType(name: String):
  case MAIN extends EventType("main")
  case BACKGROUND extends EventType("background")
  case GC extends EventType("GC")

sealed trait ProfileReporter {
  def reportBackground(profiler: Profiler, threadRange: ProfileRange): Unit
  def reportForeground(profiler: Profiler, threadRange: ProfileRange): Unit
  def reportGc(data: GcEventData): Unit
  def header(profiler: Profiler): Unit
  def close(profiler: Profiler): Unit
}
