package dotty.tools.dotc

import dotty.tools.vulpix.*
import org.junit.Test
import org.junit.Ignore

@Ignore class Playground:
  import TestConfiguration.*
  import CompilationTests.*

  @Test def example: Unit =
    implicit val testGroup: TestGroup = TestGroup("playground")
    compileFile("tests/playground/example.scala", defaultOptions).checkCompile()
