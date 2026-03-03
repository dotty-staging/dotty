package dotty.tools.dotc

import dotty.tools.FatalError
import config.CompilerCommand
import core.Comments.{ContextDoc, ContextDocstrings}
import core.Contexts.*
import core.{MacroClassLoader, TypeError}
import dotty.tools.dotc.ast.Positioned
import dotty.tools.io.{AbstractFile, FileExtension}
import reporting.*
import core.Decorators.*
import util.chaining.*

import scala.util.control.NonFatal
import fromtasty.{TASTYCompiler, TastyFileUtil}

/** Run the Dotty compiler — Scala.js version (no sys.exit). */
class Driver {

  protected def newCompiler(using Context): Compiler =
    if (ctx.settings.fromTasty.value) new TASTYCompiler
    else new Compiler

  protected def emptyReporter: Reporter = new StoreReporter(null)

  protected def doCompile(compiler: Compiler, files: List[AbstractFile])(using Context): Reporter =
    if files.nonEmpty then
      var runOrNull = ctx.run
      try
        val run = compiler.newRun
        runOrNull = run
        run.compile(files)
        finish(compiler, run)
      catch
        case ex: FatalError =>
          report.error(ex.getMessage) // signals that we should fail compilation.
        case ex: Throwable if ctx.usedBestEffortTasty =>
          report.bestEffortError(ex, "Some best-effort tasty files were not able to be read.")
          throw ex
        case ex: TypeError if !runOrNull.enrichedErrorMessage =>
          println(runOrNull.enrichErrorMessage(s"${ex.toMessage} while compiling ${files.map(_.path).mkString(", ")}"))
          throw ex
        case ex: Throwable if !runOrNull.enrichedErrorMessage =>
          println(runOrNull.enrichErrorMessage(s"Exception while compiling ${files.map(_.path).mkString(", ")}"))
          throw ex
    ctx.reporter

  protected def finish(compiler: Compiler, run: Run)(using Context): Unit =
    run.printSummary()
    if !ctx.reporter.errorsReported && run.suspendedUnits.nonEmpty then
      val suspendedUnits = run.suspendedUnits.toList
      if (ctx.settings.XprintSuspension.value)
        val suspendedHints = run.suspendedHints.toList
        report.echo(i"compiling suspended $suspendedUnits%, %")
        for (unit, (hint, atInlining)) <- suspendedHints do
          report.echo(s"  $unit at ${if atInlining then "inlining" else "typer"}: $hint")
      val run1 = compiler.newRun
      run1.compileSuspendedUnits(suspendedUnits, !run.suspendedAtTyperPhase)
      finish(compiler, run1)(using MacroClassLoader.init(ctx.fresh))

  protected def initCtx: Context = (new ContextBase).initialCtx

  protected def sourcesRequired: Boolean = true

  protected def command: CompilerCommand = ScalacCommand

  def setup(args: Array[String], rootCtx: Context): Option[(List[AbstractFile], Context)] = {
    val ictx = rootCtx.fresh
    val summary = command.distill(args, ictx.settings)(ictx.settingsState)(using ictx)
    ictx.setSettings(summary.sstate)
    MacroClassLoader.init(ictx)
    Positioned.init(using ictx)

    inContext(ictx):
      if !ctx.settings.XdropComments.value || ctx.settings.XreadComments.value then
        ictx.setProperty(ContextDoc, new ContextDocstrings)
      val fileNamesOrNone = command.checkUsage(summary, sourcesRequired)(using ctx.settings)(using ctx.settingsState)
      fileNamesOrNone.map: fileNames =>
        val files = fileNames.map(ctx.getFile)
        (files, fromTastySetup(files))
      .tap: _ =>
        if !ctx.settings.Yreporter.isDefault then
          ctx.settings.Yreporter.value match
          case "help" =>
          case reporterClassName =>
            report.error:
              em"Custom reporters via -Yreporter are not supported on Scala.js"
  }

  protected def fromTastySetup(files: List[AbstractFile])(using Context): Context =
    if ctx.settings.fromTasty.value then
      val newEntries: List[String] = files
        .flatMap { file =>
          if !file.exists then
            report.error(em"File does not exist: ${file.path}")
            None
          else file.ext match
            case FileExtension.Jar => Some(file.path)
            case FileExtension.Tasty | FileExtension.Betasty =>
              TastyFileUtil.getClassPath(file, ctx.withBestEffortTasty) match
                case Some(classpath) => Some(classpath)
                case _ =>
                  report.error(em"Could not load classname from: ${file.path}")
                  None
            case _ =>
              report.error(em"File extension is not `tasty` or `jar`: ${file.path}")
              None
        }
        .distinct
      val ctx1 = ctx.fresh
      val fullClassPath =
        (newEntries :+ ctx.settings.classpath.value).mkString(java.io.File.pathSeparator)
      ctx1.setSetting(ctx1.settings.classpath, fullClassPath)
    else ctx

  final def process(args: Array[String], simple: interfaces.SimpleReporter | Null,
    callback: interfaces.CompilerCallback | Null): interfaces.ReporterResult = {
    val reporter = if (simple == null) null else Reporter.fromSimpleReporter(simple)
    process(args, reporter, callback)
  }

  final def process(args: Array[String], reporter: Reporter | Null = null,
    callback: interfaces.CompilerCallback | Null = null): Reporter = {
    val compileCtx = initCtx.fresh
    if (reporter != null)
      compileCtx.setReporter(reporter)
    if (callback != null)
      compileCtx.setCompilerCallback(callback)
    process(args, compileCtx)
  }

  def process(args: Array[String]): Reporter =
    process(args, null: Reporter | Null, null: interfaces.CompilerCallback | Null)

  def process(args: Array[String], rootCtx: Context): Reporter = {
    setup(args, rootCtx) match
      case Some((files, compileCtx)) =>
        doCompile(newCompiler(using compileCtx), files)(using compileCtx)
      case None =>
        rootCtx.reporter
  }

  def main(args: Array[String]): Unit = {
    val _ = NonFatal
    // Don't call sys.exit on Scala.js — just return
    process(args)
  }
}
