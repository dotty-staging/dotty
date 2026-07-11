package scala.reflect
package runtime

import scala.tools.nsc.settings.ScalaVersion

/** Scala 3 port: test-only replacement for scala/scala's
 *  `scala.tools.nsc.symtab.SymbolTableForUnitTesting`, which builds a symbol table from
 *  the classpath using the Scala 2 compiler's classfile parser. Here the symbol table is
 *  backed by runtime reflection instead (a `JavaUniverse`), which provides the same
 *  `internal.SymbolTable` API surface to the tests without depending on scala-compiler.
 *
 *  It lives in `scala.reflect.runtime` (rather than in the test's own package) because it
 *  needs access to the `private[reflect]` `Settings` class to expose the `source` setting
 *  that some tests manipulate.
 */
class SymbolTableForUnitTestingBase extends JavaUniverse {

  class TestSettings extends Settings {
    object source {
      var value: ScalaVersion = ScalaVersion("2.13")
    }
  }

  override lazy val settings: TestSettings = new TestSettings
}
