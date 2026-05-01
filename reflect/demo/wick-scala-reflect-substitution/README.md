# Wick `scala-reflect_3` substitution demo

This is a small sbt project based on the Wick README demo from
https://github.com/joan38/wick.

It demonstrates the intended classpath shape for scala/scala3#25896:

- use Wick and Spark from Maven Central,
- exclude Spark's transitive Scala 2.13 `scala-reflect`,
- add this branch's `org.scala-lang::scala-reflect` artifact instead.

From the repository root, publish the local `scala-reflect_3` artifact first.
Because the demo compiles against this branch's unpublished Scala snapshot, it
also needs the local compiler bridge available to sbt:

```bash
sbt --client scala3-reflect/publishLocal scala3-sbt-bridge-bootstrapped/publishLocalBin
```

Then run the demo:

```bash
cd reflect/demo/wick-scala-reflect-substitution
sbt checkScalaReflectSubstitution run
```

If the local artifact was published with a different version, pass it with:

```bash
sbt -Dscala.reflect.demo.version=<version> checkScalaReflectSubstitution run
```
