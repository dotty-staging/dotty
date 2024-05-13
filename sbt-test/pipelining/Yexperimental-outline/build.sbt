// early out is a jar
lazy val a = project.in(file("a"))
  .settings(
    scalacOptions ++= Seq(
      "-Yexperimental-outline",
      // test of manually setting the outline-classpath (usually automatically done in the second pass)
      "-Youtline-classpath", ((ThisBuild / baseDirectory).value / "a-early.jar").toString,
      "-Yearly-tasty-output", ((ThisBuild / baseDirectory).value / "a-early.jar").toString,
      "-Ycheck:all"
    )
  )

// early out is a jar
lazy val aCheck = project.in(file("a-check"))
  .settings(
    scalacOptions ++= Seq("-Ytest-pickler", "-Ytest-pickler-check"),
    Compile / sources := (a / Compile / sources).value, // use the same sources as a
    scalacOptions ++= Seq(
      "-Yexperimental-outline",
      "-Yearly-tasty-output", ((ThisBuild / baseDirectory).value / "a-check-early.jar").toString,
    )
  )

// reads classpaths from early tasty outputs. No need for extra flags as the full tasty is available.
lazy val b = project.in(file("b"))
  .settings(
    Compile / unmanagedClasspath += Attributed.blank((ThisBuild / baseDirectory).value / "a-early.jar"),
    scalacOptions += "-Ycheck:all",
  )
