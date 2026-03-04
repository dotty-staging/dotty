package dotty.tools
package dotc

import scala.scalajs.js

/** JS-friendly entry point that reads args from process.argv on Node.js. */
object Main extends Driver {
  override def main(args: Array[String]): Unit = {
    // When called from scalaJSUseMainModuleInitializer, args is empty.
    // Read actual CLI args from Node.js process.argv (dropping node + script path).
    val actualArgs =
      if args.nonEmpty then args
      else
        try
          val argv = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]]
          argv.jsSlice(2).toArray
        catch case _: Throwable => args

    val argsWithClasspath = injectBundledClasspath(actualArgs)
    process(argsWithClasspath)
  }

  /** Auto-detect bundled lib/ directory next to main.js and inject classpath. */
  private def injectBundledClasspath(args: Array[String]): Array[String] = {
    try {
      val fs = js.Dynamic.global.require("fs")
      val path = js.Dynamic.global.require("path")
      // __dirname is module-scoped, not on global; derive from process.argv[1] instead
      val scriptPath = js.Dynamic.global.process.argv.selectDynamic("1").asInstanceOf[String]
      val dirname = path.dirname(path.resolve(scriptPath)).asInstanceOf[String]
      // lib/ is a sibling of the fastopt directory containing main.js
      val libDir = path.join(dirname, "..", "lib").asInstanceOf[String]

      val jdkDir = path.join(libDir, "jdk").asInstanceOf[String]
      val scalaLibDir = path.join(libDir, "scala-lib").asInstanceOf[String]

      // Check bundled directories exist
      val allExist =
        fs.existsSync(jdkDir).asInstanceOf[Boolean] &&
        fs.existsSync(scalaLibDir).asInstanceOf[Boolean]

      if !allExist then return args

      val bundledCp = s"$jdkDir:$scalaLibDir"

      // Check if user already provided -classpath/-cp
      val cpIdx = args.indexWhere(a => a == "-classpath" || a == "-cp")
      if cpIdx >= 0 && cpIdx + 1 < args.length then
        // Merge: bundled + user classpath
        val userCp = args(cpIdx + 1)
        val merged = s"$bundledCp:$userCp"
        args.updated(cpIdx + 1, merged)
      else
        // Prepend -classpath with bundled paths
        Array("-classpath", bundledCp) ++ args
    } catch {
      case _: Throwable => args
    }
  }
}
