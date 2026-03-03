package dotty.tools

/** Stub MainGenericCompiler for Scala.js - scripting not supported */
object MainGenericCompiler:
  def main(args: Array[String]): Unit =
    dotc.Main.process(args)
