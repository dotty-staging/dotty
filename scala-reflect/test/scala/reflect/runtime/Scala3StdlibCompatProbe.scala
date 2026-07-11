package scala.reflect.runtime

/** Executed in a forked JVM whose class path contains only this scala-reflect build and a
 *  Scala 3-compiled standard library (no Scala 2-compiled classes, hence no Scala 2
 *  pickles at all) — the setup of https://github.com/scala/scala3/issues/25896, found via
 *  the test suite of https://github.com/joan38/wick, where Apache Spark's catalyst
 *  `ScalaReflection` initializes the scala-reflect runtime universe on a Scala 3.8+
 *  class path and used to die with `class Array does not have a member apply`.
 *
 *  Prints `scala3-classpath-ok` and exits 0 on success; any exception fails the run.
 */
object Scala3StdlibCompatProbe {
  def main(args: Array[String]): Unit = {
    // The minimized reproduction from scala/scala3#25896. Referencing the universe runs
    // JavaUniverse.init() and JavaUniverseForce.force(), which eagerly resolves every
    // lazy symbol of Definitions against the class path.
    val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe
    import universe._

    val mirror = runtimeMirror(Scala3StdlibCompatProbe.getClass.getClassLoader)

    // The exact symbol whose lookup failed in the issue: the `apply` overloads of the
    // `scala.Array` companion have no static forwarders, so they are invisible to a
    // completer that only reads the companion class.
    val arrayApply = mirror.staticModule("scala.Array").info.member(TermName("apply"))
    assert(arrayApply.alternatives.nonEmpty, "scala.Array does not have a member apply")

    // The moves Spark's ScalaReflection makes on the universe:
    // mirrors, staticClass/staticModule lookups, type construction and dealiasing.
    val optionClass = mirror.staticClass("scala.Option")
    val someType    = mirror.staticClass("scala.Some").toType
    assert(someType.baseClasses.contains(optionClass), s"Some does not derive from Option: ${someType.baseClasses}")

    // TypeTags materialized in Scala 3 (see scala.tools.testkit.TypeTagMaterializer),
    // then used for subtype checks — parents are reconstructed from Java generic
    // signatures when no pickles are present.
    {
      import scala.tools.testkit.TypeTagMaterializer.given
      val listOfInt = typeOf[List[Int]]
      assert(listOfInt <:< typeOf[scala.collection.immutable.Seq[Int]],
             s"$listOfInt is not a subtype of Seq[Int]")
      assert(appliedType(mirror.staticClass("scala.Option").toTypeConstructor, definitions.IntTpe) =:= typeOf[Option[Int]],
             "appliedType(Option, Int) != typeOf[Option[Int]]")
    }

    // Reflective instantiation and invocation, java.lang and Scala classes.
    val sym  = mirror.classSymbol(classOf[java.lang.StringBuilder])
    val ctor = sym.toType.decl(termNames.CONSTRUCTOR).alternatives
      .map(_.asMethod).find(_.paramLists.flatten.isEmpty).get
    val sb = mirror.reflectClass(sym).reflectConstructor(ctor).apply()
    assert(sb.isInstanceOf[java.lang.StringBuilder], s"unexpected instance: $sb")

    println("scala3-classpath-ok")
  }
}
