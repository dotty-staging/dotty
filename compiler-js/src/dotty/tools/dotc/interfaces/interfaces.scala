package dotty.tools.dotc.interfaces

import java.util.Optional

/** Scala stubs for Java interfaces from the interfaces/ module.
 *
 *  Java interfaces allow callers to use either `x.foo()` or `x.foo` syntax.
 *  Scala traits enforce one convention. Since the majority of call sites in
 *  compiler/src/ omit parens, these stubs declare methods parameterless to
 *  avoid needing overrides at every call site.
 *
 *  The trade-off: a few implementation files (e.g. SourceFile.scala) that
 *  originally used `def content(): ...` must be overridden to drop the parens.
 */

trait AbstractFile {
  def name: String
  def path: String
  def jfile: Optional[java.io.File]
}

trait SourceFile extends AbstractFile {
  def content: Array[Char]
}

trait SourcePosition {
  def lineContent: String
  def point: Int
  def line: Int
  def column: Int
  def start: Int
  def startLine: Int
  def startColumn: Int
  def end: Int
  def endLine: Int
  def endColumn: Int
  def source: SourceFile
}

trait Diagnostic {
  def message: String
  def level: Int
  def position: Optional[SourcePosition]
  def diagnosticRelatedInformation: java.util.List[DiagnosticRelatedInformation]
}

object Diagnostic {
  val ERROR: Int = 2
  val WARNING: Int = 1
  val INFO: Int = 0
}

trait DiagnosticRelatedInformation {
  def position: Optional[SourcePosition]
  def message: String
}

trait ReporterResult {
  def hasErrors: Boolean
  def errorCount: Int
  def warningCount: Int
}

trait SimpleReporter {
  def report(diag: Diagnostic): Unit
}

trait CompilerCallback {
  def onClassGenerated(source: SourceFile, generatedClass: AbstractFile, className: String): Unit = ()
  def onSourceCompiled(source: SourceFile): Unit = ()
}
