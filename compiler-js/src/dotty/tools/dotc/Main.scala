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
    process(actualArgs)
  }
}
