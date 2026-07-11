package scala.reflect.runtime

import java.io.File
import java.nio.file.Paths

import org.junit.Assert._
import org.junit.Test

/** Compatibility with a pure Scala 3 class path, after scala/scala3#25896 (surfaced by the
 *  test suite of joan38/wick running Apache Spark, whose catalyst `ScalaReflection`
 *  initializes the scala-reflect runtime universe).
 *
 *  The rest of this test suite runs with the Scala 2-compiled standard library first on
 *  the class path (the classic deployment of scala-reflect). This test instead forks a
 *  JVM whose class path holds only this scala-reflect build and the Scala 3-compiled
 *  standard library — no Scala 2 pickles anywhere — and runs
 *  [[Scala3StdlibCompatProbe]] there.
 */
class Scala3StdlibCompatTest {

  @Test def universeWorksOnScala3OnlyClasspath(): Unit = {
    val javaBin = Paths.get(sys.props("java.home"), "bin", "java").toString
    def prop(name: String) = {
      val v = sys.props(name)
      assertNotNull(s"missing system property $name (set by the sbt build)", v)
      v
    }
    val classpath = List(
      prop("scalareflect.test.testClasses"),  // the probe itself + the TypeTag materializer
      prop("scalareflect.test.mainClasses"),  // this scala-reflect build
      prop("scalareflect.test.scala3Library") // the Scala 3-compiled standard library, nothing else
    ).mkString(File.pathSeparator)

    val process = new ProcessBuilder(javaBin, "-cp", classpath, "scala.reflect.runtime.Scala3StdlibCompatProbe")
      .redirectErrorStream(true)
      .start()
    val output = scala.io.Source.fromInputStream(process.getInputStream).mkString
    val exit   = process.waitFor()

    assertTrue(s"probe failed (exit=$exit):\n$output", exit == 0 && output.contains("scala3-classpath-ok"))
  }
}
