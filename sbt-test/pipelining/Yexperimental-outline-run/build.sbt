// early out is a jar
lazy val a = project.in(file("a"))
  .settings(
    scalacOptions ++= Seq(
      "-Yexperimental-outline", "-Ymax-parallelism:1",
      // test of manually setting the outline-classpath (usually automatically done in the second pass)
      "-Youtline-classpath", ((ThisBuild / baseDirectory).value / "a-early.jar").toString,
      "-Yearly-tasty-output", ((ThisBuild / baseDirectory).value / "a-early.jar").toString,
      "-Ycheck:all"
    )
  )
