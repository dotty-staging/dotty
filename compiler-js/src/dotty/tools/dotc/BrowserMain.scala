package dotty.tools.dotc

import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.typedarray._

import dotty.tools.io.{VirtualDirectory, VirtualFile, AbstractFile}
import dotty.tools.dotc.classpath.{VirtualDirectoryClassPath, AggregateClassPath}
import dotty.tools.dotc.config.JavaPlatform
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.reporting.{MessageRendering, StoreReporter, Diagnostic}

/** Browser-facing API for the Scala 3 compiler.
 *
 *  Usage from JavaScript:
 *  {{{
 *  const resp = await fetch('classpath.bin');
 *  DottyCompiler.loadClasspath(await resp.arrayBuffer());
 *  const result = DottyCompiler.compile("object Hello { val x: Int = 42 }", []);
 *  }}}
 */
@JSExportTopLevel("DottyCompiler")
object BrowserMain:

  private var classpathDir: VirtualDirectory | Null = null

  /** Load the classpath archive into memory. Must be called before compile(). */
  @JSExport
  def loadClasspath(buffer: ArrayBuffer): Unit =
    classpathDir = ClasspathBlob.load(buffer)

  /** Compile a Scala source string.
   *
   *  @param source  The Scala source code as a string
   *  @param args    Additional compiler arguments (e.g., ["-Xprint:typer"])
   *  @return        Array of diagnostic objects with severity, line, column, message fields
   */
  @JSExport
  def compile(source: String, args: js.Array[String]): js.Array[js.Dynamic] =
    val cpDir = classpathDir
    if cpDir == null then
      val err = js.Dynamic.literal(
        severity = "error",
        line = 0,
        column = 0,
        message = "Classpath not loaded. Call loadClasspath() first."
      )
      return js.Array(err)

    // Create a virtual source file
    val sourceFile = new VirtualFile("input.scala", "input.scala")
    val out = sourceFile.output
    out.write(source.getBytes("UTF-8"))
    out.close()

    // Create a custom driver that injects the blob classpath and virtual source file
    val driver = new BrowserDriver(cpDir, sourceFile)
    val reporter = new StoreReporter()
    val compilerArgs = args.toArray
    driver.process(compilerArgs, reporter)

    // Collect diagnostics with full rendered messages (including source context)
    val results = js.Array[js.Dynamic]()
    val ctx = driver.lastContext
    if ctx != null then
      given Context = ctx
      val rendering = new MessageRendering {}
      val diags = reporter.removeBufferedMessages
      for diag <- diags do
        val severity = diag.level match
          case 0 => "info"
          case 1 => "warning"
          case 2 => "error"
          case _ => "error"
        val pos = diag.pos
        val line = if pos.exists then pos.line else -1
        val col = if pos.exists then pos.column else -1
        val rendered = rendering.messageAndPos(diag)
        results.push(js.Dynamic.literal(
          severity = severity,
          line = line,
          column = col,
          message = rendered
        ))

    results


/** Custom Driver that injects a VirtualDirectory-based classpath and
 *  compiles a virtual source file directly (bypassing filesystem). */
private class BrowserDriver(cpDir: VirtualDirectory, sourceFile: AbstractFile) extends Driver:

  var lastContext: Context | Null = null

  override protected def initCtx: Context =
    val base = new ContextBase:
      override protected def newPlatform(using Context): JavaPlatform =
        new JavaPlatform:
          override def classPath(using Context): dotty.tools.io.ClassPath =
            VirtualDirectoryClassPath(cpDir)
    base.initialCtx

  // No source files required from CLI args — we supply them directly
  override protected def sourcesRequired: Boolean = false

  override def process(args: Array[String], rootCtx: Context): reporting.Reporter =
    lastContext = rootCtx
    setup(args, rootCtx) match
      case Some((_, compileCtx)) =>
        lastContext = compileCtx
        // Pass our virtual source file directly to the compiler
        doCompile(newCompiler(using compileCtx), List(sourceFile))(using compileCtx)
      case None =>
        rootCtx.reporter
