package dotty.tools.io

import scala.language.unsafeNulls

import dotty.tools.io.AbstractFile
import dotty.tools.io.JarArchive
import dotty.tools.io.PlainFile

import java.io.{DataOutputStream, IOException}

import dotty.tools.dotc.core.Contexts, Contexts.Context
import dotty.tools.dotc.core.Decorators.em

import dotty.tools.dotc.util.{SourcePosition, NoSourcePosition}

import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.report

import scala.annotation.constructorOnly
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ConcurrentModificationException

object FileWriters {
  type InternalName = String
  type NullableFile = AbstractFile | Null

  inline def ctx(using ReadOnlyContext): ReadOnlyContext = summon[ReadOnlyContext]

  sealed trait DelayedReporter {
    def hasErrors: Boolean
    def error(message: Context ?=> Message, position: SourcePosition): Unit
    def warning(message: Context ?=> Message, position: SourcePosition): Unit
    def log(message: String): Unit

    final def toBuffered: Option[BufferingReporter] = this match
      case buffered: BufferingReporter =>
        if buffered.hasReports then Some(buffered) else None
      case _: EagerReporter => None

    def error(message: Context ?=> Message): Unit = error(message, NoSourcePosition)
    def warning(message: Context ?=> Message): Unit = warning(message, NoSourcePosition)
    final def exception(reason: Context ?=> Message, throwable: Throwable): Unit =
      error({
        val trace = throwable.getStackTrace().mkString("\n  ")
        em"An unhandled exception was thrown in the compiler while\n  ${reason.message}.\n${throwable}\n  $trace"
      }, NoSourcePosition)
  }

  final class EagerReporter(using captured: Context) extends DelayedReporter:
    private var _hasErrors = false

    def hasErrors: Boolean = _hasErrors

    def error(message: Context ?=> Message, position: SourcePosition): Unit =
      report.error(message, position)
      _hasErrors = true

    def warning(message: Context ?=> Message, position: SourcePosition): Unit =
      report.warning(message, position)

    def log(message: String): Unit = report.echo(message)

  enum Report:
    case Error(message: Context => Message, position: SourcePosition)
    case Warning(message: Context => Message, position: SourcePosition)
    case OptimizerWarning(message: Context => Message, site: String, position: SourcePosition)
    case Log(message: String)

  final class BufferingReporter extends DelayedReporter {
    private val _bufferedReports = AtomicReference(List.empty[Report])
    private val _hasErrors = AtomicBoolean(false)

    private def recordError(): Unit = _hasErrors.set(true)
    private def recordReport(report: Report): Unit =
      _bufferedReports.getAndUpdate(report :: _)

    def resetReports(): List[Report] =
      val curr = _bufferedReports.get()
      if curr.nonEmpty && !_bufferedReports.compareAndSet(curr, Nil) then
        throw ConcurrentModificationException("concurrent modification of buffered reports")
      else curr

    def hasErrors: Boolean = _hasErrors.get()
    def hasReports: Boolean = _bufferedReports.get().nonEmpty

    def error(message: Context ?=> Message, position: SourcePosition): Unit =
      recordReport(Report.Error({case given Context => message}, position))
      recordError()

    def warning(message: Context ?=> Message, position: SourcePosition): Unit =
      recordReport(Report.Warning({case given Context => message}, position))

    def log(message: String): Unit =
      recordReport(Report.Log(message))
  }

  trait ReadOnlySettings:
    def jarCompressionLevel: Int
    def debug: Boolean

  trait ReadOnlyRun:
    def suspendedAtTyperPhase: Boolean

  trait ReadOnlyContext:
    val run: ReadOnlyRun
    val settings: ReadOnlySettings
    val reporter: DelayedReporter

  trait BufferedReadOnlyContext extends ReadOnlyContext:
    val reporter: BufferingReporter

  object ReadOnlyContext:
    def readSettings(using ctx: Context): ReadOnlySettings = new:
      val jarCompressionLevel = ctx.settings.XjarCompressionLevel.value
      val debug = ctx.settings.Ydebug.value

    def readRun(using ctx: Context): ReadOnlyRun = new:
      val suspendedAtTyperPhase = ctx.run.suspendedAtTyperPhase

    def buffered(using Context): BufferedReadOnlyContext = new:
      val settings = readSettings
      val reporter = BufferingReporter()
      val run = readRun

    def eager(using Context): ReadOnlyContext = new:
      val settings = readSettings
      val reporter = EagerReporter()
      val run = readRun

  sealed trait TastyWriter {
    def writeTasty(name: InternalName, bytes: Array[Byte])(using ReadOnlyContext): NullableFile
    def close(): Unit

    protected def classToRelativePath(className: InternalName): String =
      className.replace('.', '/') + ".tasty"
  }

  object TastyWriter {
    def apply(output: AbstractFile)(using ReadOnlyContext): TastyWriter = {
      val basicTastyWriter = new SingleTastyWriter(FileWriter(output, None))
      basicTastyWriter
    }

    private final class SingleTastyWriter(underlying: FileWriter) extends TastyWriter {
      override def writeTasty(className: InternalName, bytes: Array[Byte])(using ReadOnlyContext): NullableFile =
        underlying.writeFile(classToRelativePath(className), bytes)
      override def close(): Unit = underlying.close()
    }
  }

  sealed trait FileWriter {
    def writeFile(relativePath: String, bytes: Array[Byte])(using ReadOnlyContext): NullableFile
    def close(): Unit
  }

  object FileWriter {
    def apply(file: AbstractFile, jarManifestMainClass: Option[String])(using ReadOnlyContext): FileWriter =
      if (file.isVirtual) new VirtualFileWriter(file)
      else throw new IllegalStateException(s"Only virtual file output is supported on Scala.js, got: $file [${file.getClass}]")
  }

  private final class VirtualFileWriter(base: AbstractFile) extends FileWriter {
    private def getFile(base: AbstractFile, path: String): AbstractFile = {
      def ensureDirectory(dir: AbstractFile): AbstractFile =
        if (dir.isDirectory) dir
        else throw new FileConflictException(s"${base.path}/${path}: ${dir.path} is not a directory")
      val components = path.split('/')
      var dir = base
      for i <- 0 until components.length - 1 do
        dir = ensureDirectory(dir).subdirectoryNamed(components(i).toString)
      ensureDirectory(dir).fileNamed(components.last.toString)
    }

    private def writeBytes(outFile: AbstractFile, bytes: Array[Byte]): Unit = {
      val out = new DataOutputStream(outFile.bufferedOutput)
      try out.write(bytes, 0, bytes.length)
      finally out.close()
    }

    override def writeFile(relativePath: String, bytes: Array[Byte])(using ReadOnlyContext): NullableFile = {
      val outFile = getFile(base, relativePath)
      writeBytes(outFile, bytes)
      outFile
    }
    override def close(): Unit = ()
  }

  class FileConflictException(msg: String, cause: Throwable = null) extends IOException(msg, cause)
}
