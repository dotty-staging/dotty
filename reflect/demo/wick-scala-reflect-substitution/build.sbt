lazy val scalaReflectVersion =
  sys.props.getOrElse("scala.reflect.demo.version", "3.9.0-RC1-bin-SNAPSHOT")

ThisBuild / scalaVersion := scalaReflectVersion

lazy val checkScalaReflectSubstitution = taskKey[Unit](
  "Checks that the demo classpath uses scala-reflect_3 and excludes scala-reflect 2.13."
)

lazy val root = (project in file("."))
  .settings(
    name := "wick-scala-reflect-substitution-demo",
    Compile / scalaCompilerBridgeBinaryJar := {
      val bridge = Path.userHome / ".ivy2" / "local" / "org.scala-lang" / "scala3-sbt-bridge" /
        scalaReflectVersion / "jars" / "scala3-sbt-bridge.jar"
      if (!bridge.isFile)
        sys.error(s"Expected local scala3-sbt-bridge for $scalaReflectVersion at $bridge")
      Some(bridge)
    },
    Compile / run / fork := true,
    Compile / run / javaOptions ++= Seq(
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    libraryDependencies ++= Seq(
      "com.netflix.wick" %% "wick" % "0.0.4",
      ("org.apache.spark" %% "spark-sql" % "3.5.7")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang", "scala-reflect"),
      "org.scala-lang" %% "scala-reflect" % scalaReflectVersion
    ),
    dependencyOverrides += "org.scala-lang" %% "scala-reflect" % scalaReflectVersion,
    excludeDependencies += ExclusionRule("org.scala-lang", "scala-reflect"),
    checkScalaReflectSubstitution := {
      val log = streams.value.log
      val reflectJars = (Compile / dependencyClasspath).value.files
        .filter(file => file.getName.startsWith("scala-reflect"))

      val hasScalaReflect3 = reflectJars.exists(_.getName.startsWith("scala-reflect_3"))
      val hasScalaReflect213 = reflectJars.exists(_.getName.matches("scala-reflect-2\\.13\\..*\\.jar"))

      if (!hasScalaReflect3)
        sys.error(s"Expected scala-reflect_3 $scalaReflectVersion on the classpath; found: ${reflectJars.mkString(", ")}")
      if (hasScalaReflect213)
        sys.error(s"Expected Spark's scala-reflect 2.13 dependency to be excluded; found: ${reflectJars.mkString(", ")}")

      log.info(s"scala-reflect substitution OK: ${reflectJars.mkString(", ")}")
    }
  )
