package scala.reflect.compat

import org.junit.Test

object Issue25896ScalaReflection:
  val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe

class Issue25896Test:
  @Test def initializesRuntimeUniverseFromScala3Code(): Unit =
    val _ = Issue25896ScalaReflection.universe
