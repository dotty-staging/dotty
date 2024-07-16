package dotty.tools
package dotc
package transform

import core.*
import Contexts.*
import Decorators.*
import tasty.*
import config.Printers.{noPrinter, pickling}
import config.Feature
import java.io.PrintStream
import io.FileWriters.{TastyWriter, ReadOnlyContext}
import StdNames.{str, nme}
import Periods.*
import Phases.*
import Symbols.*
import StdNames.str
import Flags.Module
import reporting.{ThrowingReporter, Profile, Message}
import collection.mutable
import util.concurrent.{Executor, Future}
import compiletime.uninitialized
import dotty.tools.io.{JarArchive, AbstractFile}
import dotty.tools.dotc.printing.OutlinePrinter
import scala.annotation.constructorOnly
import scala.concurrent.Promise
import dotty.tools.dotc.transform.Pickler.writeSigFilesAsync

import scala.util.chaining.given
import dotty.tools.io.FileWriters.{EagerReporter, BufferingReporter}
import dotty.tools.dotc.sbt.interfaces.IncrementalCallback
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import dotty.tools.dotc.sbt.{asyncReportAPIPhaseCompleted, asyncReportDepPhaseCompleted}

object Pickler {
  val name: String = "pickler"
  val description: String = "generates TASTy info"

  /** If set, perform jump target compacting, position and comment pickling,
   *  as well as final assembly in parallel with downstream phases; force
   *  only in backend.
   */
  inline val ParallelPickling = true

  /**A holder for syncronization points and reports when writing TASTy asynchronously.
   * The callbacks should only be called once.
   */
  class AsyncTastyHolder private (
      val earlyOut: AbstractFile,
      promises: AsyncTastyHolder.PromiseState,
      initial: AsyncTastyHolder.State)(using @constructorOnly ex: ExecutionContext):
    import scala.concurrent.Future as StdFuture
    import scala.concurrent.Await

    private val backendFuture: StdFuture[Option[AsyncTastyHolder.State]] = promises.initSyncFuture

    def cancel(): Unit =
      promises.cancel()

    def cancelled: Boolean = promises.cancelled

    /** awaits the state of async TASTy operations indefinitely, returns optionally any buffered reports. */
    def sync()(using Context): Option[BufferingReporter] =
      import scala.concurrent.duration.given
      try
        val state = Await.result(backendFuture, 2.seconds)
        state.flatMap(_.pending)
      catch
        case err: java.util.concurrent.TimeoutException =>
          val trace = err.getStackTrace().nn.mkString("\n  ", "\n  ", "\n")
          report.error(s"Timeout waiting for async TASTy operations to complete:$trace")
          None

    def signalAPIComplete(done: Boolean): Unit =
      promises.signalAPIComplete(done)

    def signalDepsComplete(done: Boolean): Unit =
      promises.signalDepsComplete(done)

    /** should only be called once */
    def signalAsyncTastyWritten()(using ctx: ReadOnlyContext): Unit =
      promises.signalAsyncTastyWritten(initial, earlyOut)

  end AsyncTastyHolder

  object AsyncTastyHolder:
    import scala.concurrent.Future as StdFuture

    enum PromiseState:
      private val _cancelled = AtomicBoolean(false)


      /** check if the work has been cancelled. */
      def cancelled: Boolean = _cancelled.get()

      /**Cancel any outstanding work.
       * This should be done at the end of a run, e.g. background work may be running even though
       * errors in main thread will prevent reaching the backend. */
      def cancel(): Unit =
        if _cancelled.compareAndSet(false, true) then
          doAsyncTasty(_.trySuccess(None))
          doAsyncAPI(_.trySuccess(Signal.Cancelled))
          doAsyncDeps(_.trySuccess(Signal.Cancelled))
        else
          () // nothing else to do

      private def resolveSignal(done: Boolean, promise: Promise[Signal]): Unit =
        promise.trySuccess(if done then Signal.Effect else Signal.Passive)

      def signalAPIComplete(done: Boolean): Unit =
        this match
          case OutlineTyper(_, _, asyncAPIComplete) => resolveSignal(done, asyncAPIComplete)
          case StdPipelined(_, _, asyncAPIComplete, _) => resolveSignal(done, asyncAPIComplete)
          case PostOutline(_, _) | WriteOnly(_) =>
            assert(false, s"API should not be signalled for state $productPrefix")

      def signalDepsComplete(done: Boolean): Unit = this match
        case PostOutline(_, asyncDepsComplete) => resolveSignal(done, asyncDepsComplete)
        case StdPipelined(_, _, _, asyncDepsComplete) => resolveSignal(done, asyncDepsComplete)
        case OutlineTyper(_, _, _) | WriteOnly(_) =>
          assert(false, s"Deps should not be signalled for state $productPrefix")

      /** should only be called once */
      def signalAsyncTastyWritten(initial: State, earlyOut: AbstractFile)(using ctx: ReadOnlyContext): Unit =
        def doSignal(asyncTastyWritten: Promise[Option[State]]): Unit = {
          val done = !ctx.run.suspendedAtTyperPhase
          if done then
            try
              // when we are done, i.e. no suspended units,
              // we should close the file system so it can be read in the same JVM process.
              // Note: we close even if we have been cancelled.
              earlyOut match
                case jar: JarArchive => jar.close()
                case _ =>
            catch
              case NonFatal(t) =>
                ctx.reporter.error(em"Error closing early output: ${t}")

          asyncTastyWritten.trySuccess:
            Some(
              initial.copy(
                hasErrors = ctx.reporter.hasErrors,
                pending = ctx.reporter.toBuffered
              )
            )
        }

        this match
          case WriteOnly(asyncTastyWritten) => doSignal(asyncTastyWritten)
          case OutlineTyper(asyncTastyWritten, _, _) => doSignal(asyncTastyWritten)
          case StdPipelined(asyncTastyWritten, _, _, _) => doSignal(asyncTastyWritten)
          case PostOutline(_, _) =>
            assert(false, s"AsyncTastyWritten should not be signalled for state $productPrefix")
      end signalAsyncTastyWritten

      /** Set up any side effects that should be performed when  */
      private[Pickler] def initSyncFuture(using ExecutionContext): StdFuture[Option[State]] = this match
        case WriteOnly(asyncTastyWritten) => asyncTastyWritten.future
        case OutlineTyper(asyncTastyWritten, incCallback, asyncAPIComplete) =>
          // Steps:
          // 1. Outline TASTy begins writing
          // 2. API phase is completed
          // 3. run ends, cancelling any outstanding work

          asyncAPIComplete.future
            .zipWith(asyncTastyWritten.future): (api, state) =>
              if api == Signal.Cancelled then None
              else state.map(_ -> api)
            .map: optStateApi =>
              optStateApi.map: (state, api) =>
                if incCallback != null && api == Signal.Effect && !state.hasErrors then
                  val reporter = asyncReportAPIPhaseCompleted(incCallback, state.pending)
                  state.copy(
                    hasErrors = reporter.hasErrors,
                    pending = reporter.toBuffered
                  )
                else state
        case PostOutline(incCallback, asyncDepsComplete) =>
          // Steps:
          // 1. Deps phase is completed - signal to zinc
          // 2. run ends, cancelling any outstanding work
          asyncDepsComplete.future.map: s =>
            Option.when(s != Signal.Cancelled):
              if incCallback != null && s == Signal.Effect then
                val reporter = asyncReportDepPhaseCompleted(incCallback, None)
                // TODO: when we switch to batch compile - then this thing should further delegate to
                // another promise that is awaited by the driver. OR - we dont have any effect here -
                // and the promise is passed in externally.
                State(hasErrors = reporter.hasErrors, pending = reporter.toBuffered)
              else
                State(hasErrors = false, pending = None)



        case StdPipelined(asyncTastyWritten, incCallback, asyncAPIComplete, asyncDepsComplete) =>
          // Steps:
          // 1. Deps phase is completed - wait to signal to zinc until API is complete
          // 2. Outline TASTy begins writing
          // 3. API phase is completed - signal API to zinc, then signal Deps to zinc
          // 3. run ends, cancelling any outstanding work

          /* Zinc expects API to be finished first, after TASTy writing */
          val asyncAPIFuture = asyncAPIComplete.future

          /** Next, set up reporting that API phase is complete, after TASTy writing */
          val asyncAPIReported = asyncAPIFuture
            .zipWith(asyncTastyWritten.future): (api, state) =>
              if api == Signal.Cancelled then None
              else state.map(_ -> api)
            .map: optStateApi =>
              optStateApi.map: (state, api) =>
                if incCallback != null && api == Signal.Effect && !state.hasErrors then
                  val reporter = asyncReportAPIPhaseCompleted(incCallback, state.pending)
                  state.copy(hasErrors = reporter.hasErrors, pending = reporter.toBuffered)
                else state

          /* Zinc expects Deps to be finished after API */
          val asyncDepsFuture = asyncDepsComplete.future.zipWith(asyncAPIFuture)((deps, _) => deps)

          /** Next, set up reporting that Deps phase is complete, after API is reported */
          val asyncDepsReported = asyncDepsFuture
            .zipWith(asyncAPIReported): (deps, state) =>
              if deps == Signal.Cancelled then None
              else state.map(_ -> deps)
            .map: optStateDeps =>
              optStateDeps.map: (state, deps) =>
                if incCallback != null && deps == Signal.Effect && !state.hasErrors then
                  val reporter = asyncReportDepPhaseCompleted(incCallback, state.pending)
                  state.copy(hasErrors = reporter.hasErrors, pending = reporter.toBuffered)
                else state

          asyncDepsReported
      end initSyncFuture


      private def doAsyncTasty(f: Promise[Option[State]] => Unit): Unit = this match
        case WriteOnly(asyncTastyWritten) => f(asyncTastyWritten)
        case OutlineTyper(asyncTastyWritten, _, _) => f(asyncTastyWritten)
        case PostOutline(_, _) => ()
        case StdPipelined(asyncTastyWritten, _, _, _) => f(asyncTastyWritten)

      private def doAsyncAPI(f: Promise[Signal] => Unit): Unit = this match
        case WriteOnly(_) => ()
        case OutlineTyper(_, _, asyncAPIComplete) => f(asyncAPIComplete)
        case PostOutline(_, _) => ()
        case StdPipelined(_, _, asyncAPIComplete, _) => f(asyncAPIComplete)

      private def doAsyncDeps(f: Promise[Signal] => Unit): Unit = this match
        case WriteOnly(_) => ()
        case OutlineTyper(_, _, _) => ()
        case PostOutline(_, asyncDepsComplete) => f(asyncDepsComplete)
        case StdPipelined(_, _, _, asyncDepsComplete) => f(asyncDepsComplete)


      private[AsyncTastyHolder] case WriteOnly(asyncTastyWritten: Promise[Option[State]])
      private[AsyncTastyHolder] case OutlineTyper(asyncTastyWritten: Promise[Option[State]], ic: IncrementalCallback | Null, asyncAPIComplete: Promise[Signal])
      private[AsyncTastyHolder] case PostOutline(ic: IncrementalCallback | Null, asyncDepsComplete: Promise[Signal])
      private[AsyncTastyHolder] case StdPipelined(asyncTastyWritten: Promise[Option[State]], ic: IncrementalCallback | Null, asyncAPIComplete: Promise[Signal], asyncDepsComplete: Promise[Signal])

    class Promises private[AsyncTastyHolder] (
      private[AsyncTastyHolder] val asyncTastyWritten: Promise[Option[State]],
      private[AsyncTastyHolder] val asyncAPIComplete: Promise[Signal],
      private[AsyncTastyHolder] val asyncDepsComplete: Promise[Signal]
    )


    /** The state after writing async tasty. Any errors should have been reported, or pending.
     *  if suspendedUnits is true, then we can't signal Zinc yet.
     */
    case class State(
      hasErrors: Boolean,
      pending: Option[BufferingReporter]
    ):

      override def toString: String = "State(...)"

    end State

    private enum Signal:
      case Passive, Effect, Cancelled

    /**Create a holder for Asynchronous state of early-TASTy operations.
     * the `ExecutionContext` parameter is used to call into Zinc to signal
     * that API and Dependency phases are complete.
     */
    def init(using Context, ExecutionContext): AsyncTastyHolder =
      val incCallback = ctx.incCallback
      def signalPromise = Promise[Signal]()
      def optStatePromise = Promise[Option[State]]()
      val initial = State(
        hasErrors = false,
        pending = None
      )
      val promises =
        if !ctx.runZincPhases then
          PromiseState.WriteOnly(optStatePromise)
        else if ctx.isOutlineFirstPass then
          PromiseState.OutlineTyper(optStatePromise, incCallback, signalPromise)
        else if ctx.isOutlineSecondPass then
          PromiseState.PostOutline(incCallback, signalPromise)
        else
          PromiseState.StdPipelined(optStatePromise, incCallback, signalPromise, signalPromise)

      AsyncTastyHolder(ctx.settings.XearlyTastyOutput.value, promises, initial)
    end init


  /** Asynchronously writes TASTy files to the destination -Yearly-tasty-output.
   *  If no units have been suspended, then we are "done", which enables Zinc to be signalled.
   *
   *  If there are suspended units, (due to calling a macro defined in the same run), then the API is incomplete,
   *  so it would be a mistake to signal Zinc. This is a sensible default, because Zinc by default will ignore the
   *  signal if there are macros in the API.
   *  - See `sbt-test/pipelining/pipelining-scala-macro` for an example.
   *
   *  TODO: The user can override this default behaviour in Zinc to always listen to the signal,
   *  (e.g. if they define the macro implementation in an upstream, non-pipelined project).
   *  - See `sbt-test/pipelining/pipelining-scala-macro-force` where we force Zinc to listen to the signal.
   *  If the user wants force early output to be written, then they probably also want to benefit from pipelining,
   *  which then makes suspension problematic as it increases compilation times.
   *  Proposal: perhaps we should provide a flag `-Ystrict-pipelining` (as an alternative to `-Yno-suspended-units`),
   *    which fails in the condition of definition of a macro where its implementation is in the same project.
   *    (regardless of if it is used); this is also more strict than preventing suspension at typer.
   *    The user is then certain that they are always benefitting as much as possible from pipelining.
   */
  def writeSigFilesAsync(
      tasks: List[(String, Array[Byte])],
      writers: List[EarlyFileWriter],
      async: AsyncTastyHolder)(using ctx: ReadOnlyContext): Unit = {
    try
      try
        for
          (internalName, pickled) <- tasks
          writer <- writers
        do
          if !async.cancelled then
            val _ = writer.writeTasty(internalName, pickled)
      catch
        case NonFatal(t) => ctx.reporter.exception(em"writing TASTy to early output", t)
      finally
        writers.foreach(_.close())
    catch
      case NonFatal(t) => ctx.reporter.exception(em"closing early output writer", t)
    finally
      async.signalAsyncTastyWritten()
  }

  class EarlyFileWriter private (writer: TastyWriter):
    def this(dest: AbstractFile)(using @constructorOnly ctx: ReadOnlyContext) = this(TastyWriter(dest))

    export writer.{writeTasty, close}
}

/** This phase pickles trees */
class Pickler extends Phase {
  import ast.tpd.*

  private def doAsyncTasty(using Context): Boolean = ctx.run.nn.asyncTasty.isDefined

  private var fastDoAsyncTasty: Boolean = false

  override def phaseName: String = Pickler.name

  override def description: String = Pickler.description

  // No need to repickle trees coming from TASTY, however in the case that we need to write TASTy to early-output,
  // then we need to run this phase to send the tasty from compilation units to the early-output.
  override def isRunnable(using Context): Boolean =
    (super.isRunnable || ctx.isBestEffort)
    && (!ctx.settings.fromTasty.value || doAsyncTasty)
    && (!ctx.usedBestEffortTasty || ctx.isBestEffort)
    // we do not want to pickle `.betasty` if do not plan to actually create the
    // betasty file (as signified by the -Ybest-effort option)

  // when `-Xjava-tasty` is set we actually want to run this phase on Java sources
  override def skipIfJava(using Context): Boolean = false

  private def output(name: String, msg: String) = {
    val s = new PrintStream(name)
    s.print(msg)
    s.close
  }

  // Maps that keep a record if -Ytest-pickler is set.
  private val beforePickling = new mutable.HashMap[ClassSymbol, String]
  private val printedTasty = new mutable.HashMap[ClassSymbol, String]
  private val pickledBytes = new mutable.HashMap[ClassSymbol, (CompilationUnit, Array[Byte])]

  /** Drop any elements of this list that are linked module classes of other elements in the list */
  private def dropCompanionModuleClasses(clss: List[ClassSymbol])(using Context): List[ClassSymbol] = {
    val companionModuleClasses =
      clss.filterNot(_.is(Module)).map(_.linkedClass).filterNot(_.isAbsent())
    clss.filterNot(companionModuleClasses.contains)
  }

  /** Runs given functions with a scratch data block in a serialized fashion (i.e.
   *  inside a synchronized block). Scratch data is re-used between calls.
   *  Used to conserve on memory usage by avoiding to create scratch data for each
   *  pickled unit.
   */
  object serialized:
    val scratch = new ScratchData
    private val buf = mutable.ListBuffer.empty[(String, Array[Byte])]
    def run(body: ScratchData => Array[Byte]): Array[Byte] =
      synchronized {
        scratch.reset()
        body(scratch)
      }
    def commit(internalName: String, tasty: Array[Byte]): Unit = synchronized {
      buf += ((internalName, tasty))
    }
    def result(): List[(String, Array[Byte])] = synchronized {
      val res = buf.toList
      buf.clear()
      res
    }

  private val executor = Executor[Array[Byte]]()

  private def useExecutor(using Context) =
    Pickler.ParallelPickling && !ctx.isBestEffort && !ctx.settings.YtestPickler.value

  private def printerContext(isOutline: Boolean)(using Context): Context =
    if isOutline then ctx.fresh.setPrinterFn(OutlinePrinter(_))
    else ctx

  /** only ran under -Xpickle-write and -from-tasty */
  private def runFromTasty(unit: CompilationUnit)(using Context): Unit = {
    val pickled = unit.pickled
    for (cls, bytes) <- pickled do
      serialized.commit(computeInternalName(cls), bytes())
  }

  private def computeInternalName(cls: ClassSymbol)(using Context): String =
    if cls.is(Module) then cls.binaryClassName.stripSuffix(str.MODULE_SUFFIX).nn
    else cls.binaryClassName

  override def run(using Context): Unit = {
    val unit = ctx.compilationUnit
    val isBestEffort = ctx.reporter.errorsReported || ctx.usedBestEffortTasty
    val isOutlineTyper = ctx.isOutlineFirstPass
    pickling.println(i"unpickling in run ${ctx.runId}")

    if ctx.settings.fromTasty.value then
      // skip the rest of the phase, as tasty is already "pickled",
      // however we still need to set up tasks to write TASTy to
      // early output when pipelining is enabled.
      if fastDoAsyncTasty then
        runFromTasty(unit)
      return ()

    for
      cls <- dropCompanionModuleClasses(topLevelClasses(unit.tpdTree))
      tree <- sliceTopLevel(unit.tpdTree, cls)
    do
      if ctx.settings.YtestPickler.value then beforePickling(cls) =
        tree.show(using printerContext(isOutlineTyper || unit.typedAsJava))

      val sourceRelativePath =
        val reference = ctx.settings.sourceroot.value
        util.SourceFile.relativePath(unit.source, reference)
      val isJavaAttr = unit.isJava // we must always set JAVAattr when pickling Java sources
      if isJavaAttr then
        // assert that Java sources didn't reach Pickler without `-Xjava-tasty`.
        assert(ctx.settings.XjavaTasty.value, "unexpected Java source file without -Xjava-tasty")
      val isOutline = isJavaAttr || isOutlineTyper
      val attributes = Attributes(
        sourceFile = sourceRelativePath,
        scala2StandardLibrary = ctx.settings.YcompileScala2Library.value,
        explicitNulls = ctx.settings.YexplicitNulls.value,
        captureChecked = Feature.ccEnabled,
        withPureFuns = Feature.pureFunsEnabled,
        isJava = isJavaAttr,
        isOutline = isOutline
      )

      val pickler = new TastyPickler(cls, isBestEffortTasty = isBestEffort)
      val treePkl = new TreePickler(pickler, attributes)
      val successful =
        try
          treePkl.pickle(tree :: Nil)
          true
        catch
          case NonFatal(ex) if ctx.isBestEffort =>
            report.bestEffortError(ex, "Some best-effort tasty files will not be generated.")
            false
      Profile.current.recordTasty(treePkl.buf.length)

      val positionWarnings = new mutable.ListBuffer[Message]()
      def reportPositionWarnings() = positionWarnings.foreach(report.warning(_))

      val internalName = if fastDoAsyncTasty then computeInternalName(cls) else ""

      def computePickled(): Array[Byte] = inContext(ctx.fresh) {
        serialized.run { scratch =>
          treePkl.compactify(scratch)
          if tree.span.exists then
            val reference = ctx.settings.sourceroot.value
            PositionPickler.picklePositions(
                pickler, treePkl.buf.addrOfTree, treePkl.treeAnnots, reference,
                unit.source, tree :: Nil, positionWarnings,
                scratch.positionBuffer, scratch.pickledIndices)

          if !ctx.settings.XdropComments.value then
            CommentPickler.pickleComments(
                pickler, treePkl.buf.addrOfTree, treePkl.docString, tree,
                scratch.commentBuffer)

          AttributePickler.pickleAttributes(attributes, pickler, scratch.attributeBuffer)

          val pickled = pickler.assembleParts()

          def rawBytes = // not needed right now, but useful to print raw format.
            pickled.iterator.grouped(10).toList.zipWithIndex.map {
              case (row, i) => s"${i}0: ${row.mkString(" ")}"
            }

          // println(i"rawBytes = \n$rawBytes%\n%") // DEBUG
          if ctx.settings.YprintTasty.value || pickling != noPrinter then
            println(i"**** pickled info of $cls")
            println(TastyPrinter.showContents(pickled, ctx.settings.color.value == "never", isBestEffortTasty = false))
            println(i"**** end of pickled info of $cls")

          if fastDoAsyncTasty then
            serialized.commit(internalName, pickled)

          pickled
        }
      }

      if successful then
        /** A function that returns the pickled bytes. Depending on `Pickler.ParallelPickling`
         *  either computes the pickled data in a future or eagerly before constructing the
         *  function value.
         */
        val demandPickled: () => Array[Byte] =
          if useExecutor then
            val futurePickled = executor.schedule(computePickled)
            () =>
              try futurePickled.force.get
              finally reportPositionWarnings()
          else
            val pickled = computePickled()
            reportPositionWarnings()
            if ctx.settings.YtestPickler.value then
              pickledBytes(cls) = (unit, pickled)
              if ctx.settings.YtestPicklerCheck.value then
                printedTasty(cls) = TastyPrinter.showContents(pickled, noColor = true, isBestEffortTasty = false, testPickler = true)
            () => pickled

        if ctx.isOutlineFirstPass then
          unit.outlinePickled += (cls -> demandPickled)
        else // either non-outline, or second pass
          unit.pickled += (cls -> demandPickled)
        end if
      end if
    end for
  }

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] = {
    val useExecutor = this.useExecutor

    val writeTask: Option[() => Unit] =
      if ctx.isOutlineSecondPass then None // we would have written the TASTy in the first pass
      else
        ctx.run.nn.asyncTasty.map: async =>
          fastDoAsyncTasty = true
          given ReadOnlyContext = if useExecutor then ReadOnlyContext.buffered else ReadOnlyContext.eager
          val base = Pickler.EarlyFileWriter(async.earlyOut) :: Nil
          val writers =
            if ctx.isOutlineFirstPass then
              val extra = Pickler.EarlyFileWriter(ctx.settings.YoutlineClasspath.value)
              extra :: base
            else
              base
          () => writeSigFilesAsync(serialized.result(), writers, async)

    def runPhase(writeCB: (doWrite: () => Unit) => Unit) =
      super.runOn(units).tap(_ => writeTask.foreach(writeCB))

    val result =
      if useExecutor then
        executor.start()
        try
          runPhase: doWrite =>
            // unless we redesign executor to have "Unit" schedule overload, we need some sentinel value.
            executor.schedule(() => { doWrite(); Array.emptyByteArray })
        finally executor.close() // NOTE: each run will create a new Pickler phase, so safe to close here.
      else
        runPhase(_())
    if ctx.settings.YtestPickler.value then
      val ctx2 = ctx.fresh
        .setSetting(ctx.settings.XreadComments, true)
        .setSetting(ctx.settings.YshowPrintErrors, true)
      testUnpickler(
        using ctx2
          .setPeriod(Period(ctx.runId + 1, ctx.base.typerPhase.id))
          .setReporter(new ThrowingReporter(ctx.reporter))
          .addMode(Mode.ReadPositions)
      )
    if ctx.isBestEffort then
      val outpath =
        ctx.settings.outputDir.value.jpath.toAbsolutePath.nn.normalize.nn
          .resolve("META-INF").nn
          .resolve("best-effort")
      Files.createDirectories(outpath)
      BestEffortTastyWriter.write(outpath.nn, result)
    result
  }

  private def testUnpickler(using Context): Unit =
    pickling.println(i"testing unpickler at run ${ctx.runId}")
    import dotty.tools.dotc.core.NameOps.stripModuleClassSuffix
    ctx.initialize()
    val resolveCheck = ctx.settings.YtestPicklerCheck.value
    val isOutlineFirstPass = ctx.isOutlineFirstPass
    val unpicklers =
      for ((cls, (unit, bytes)) <- pickledBytes) yield {
        val unpickler = new DottyUnpickler(unit.source.file, bytes, isBestEffortTasty = false)
        unpickler.enter(roots = Set.empty)
        val optCheck =
          if resolveCheck then
            val resolved =
              val ext =
                if ctx.isOutlineFirstPass && !unit.typedAsJava then "outline.tastycheck"
                else "tastycheck"
              unit.source.file.resolveSibling(s"${cls.name.stripModuleClassSuffix.mangledString}.$ext")
            if resolved == null then None
            else Some(resolved)
          else None
        cls -> (unit, unpickler, optCheck)
      }
    pickling.println("************* entered toplevel ***********")
    val rootCtx = ctx
    for ((cls, (unit, unpickler, optCheck)) <- unpicklers) do
      val testJava = unit.typedAsJava
      if testJava then
        if unpickler.unpickler.nameAtRef.contents.exists(_ == nme.FromJavaObject) then
          report.error(em"Pickled reference to FromJavaObject in Java defined $cls in ${cls.source}")
      val unpickled = unpickler.rootTrees
      val freshUnit = CompilationUnit(rootCtx.compilationUnit.source)
      freshUnit.needsCaptureChecking = unit.needsCaptureChecking
      freshUnit.knowsPureFuns = unit.knowsPureFuns
      optCheck match
        case Some(check) =>
          import java.nio.charset.StandardCharsets.UTF_8
          val checkContents = String(check.toByteArray, UTF_8)
          inContext(rootCtx.fresh.setCompilationUnit(freshUnit)):
            testSamePrinted(printedTasty(cls), checkContents, cls, check)
        case None =>
          ()

      inContext(printerContext(testJava || isOutlineFirstPass)(using rootCtx.fresh.setCompilationUnit(freshUnit))):
        testSame(i"$unpickled%\n%", beforePickling(cls), cls)

  private def testSame(unpickled: String, previous: String, cls: ClassSymbol)(using Context) =
    import java.nio.charset.StandardCharsets.UTF_8
    def normal(s: String) = new String(s.getBytes(UTF_8), UTF_8)
    val unequal = unpickled.length() != previous.length() || normal(unpickled) != normal(previous)
    if unequal then
      output("before-pickling.txt", previous)
      output("after-pickling.txt", unpickled)
      //sys.process.Process("diff -u before-pickling.txt after-pickling.txt").!
      report.error(em"""pickling difference for $cls in ${cls.source}, for details:
                    |
                    |  diff before-pickling.txt after-pickling.txt""")
  end testSame

  private def testSamePrinted(printed: String, checkContents: String, cls: ClassSymbol, check: AbstractFile)(using Context): Unit = {
    for lines <- diff(printed, checkContents) do
      output("after-printing.txt", printed)
      report.error(em"""TASTy printer difference for $cls in ${cls.source}, did not match ${check},
                    |  output dumped in after-printing.txt, check diff with `git diff --no-index -- $check after-printing.txt`
                    |  actual output:
                    |$lines%\n%""")
  }

  /** Reuse diff logic from compiler/test/dotty/tools/vulpix/FileDiff.scala */
  private def diff(actual: String, expect: String): Option[Seq[String]] =
    import scala.util.Using
    import scala.io.Source
    val actualLines = Using(Source.fromString(actual))(_.getLines().toList).get
    val expectLines = Using(Source.fromString(expect))(_.getLines().toList).get
    Option.when(!matches(actualLines, expectLines))(actualLines)

  private def matches(actual: String, expect: String): Boolean = {
    import java.io.File
    val actual1 = actual.stripLineEnd
    val expect1  = expect.stripLineEnd

    // handle check file path mismatch on windows
    actual1 == expect1 || File.separatorChar == '\\' && actual1.replace('\\', '/') == expect1
  }

  private def matches(actual: Seq[String], expect: Seq[String]): Boolean = {
    actual.length == expect.length
    && actual.lazyZip(expect).forall(matches)
  }
}
