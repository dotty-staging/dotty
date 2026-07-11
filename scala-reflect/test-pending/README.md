# Pending tests

These tests from scala/scala's `test/junit/scala/reflect` require the Scala 2 compiler
(`scala.tools.nsc` / ToolBox / testkit facilities that compile code at runtime), which is
not available to this Scala 3 port of scala-reflect:

- `ClassOfTest.scala` — uses `scala.tools.testkit.RunTesting` (runtime compilation)
- `InferTest.scala` — uses `scala.tools.testkit.BytecodeTesting` (compiles and inspects bytecode)
- `PrintersTest.scala` — uses `scala.tools.reflect.ToolBox` (part of scala-compiler)
- `LongNamesTest.scala` — uses `scala.tools.testkit.VirtualCompiler`

They are kept here unmodified for reference/completeness.

Additionally, these tests use `typeOf[...]`/`q"..."`, i.e. the `materializeTypeTag` and
quasiquote macros that are implemented inside the Scala 2 compiler and can therefore not
be expanded when the test suite itself is compiled with Scala 3 (Scala 2-compiled client
code using these macros keeps working against this artifact — the macros are expanded by
the client's compiler):

- `QTest.scala` (quasiquotes)
- `TypesTest.scala` (`typeOf`, existential type syntax)

`FieldAccessTest.scala` runs runtime reflection on a class defined in the test suite
itself. The suite is compiled with Scala 3, so that class carries no Scala 2 pickle
(ScalaSignature) and scala-reflect — including the original Scala 2-compiled one — can
only see its Java-level members. In scala/scala the suite is compiled by Scala 2, where
the reflected class is pickled.
