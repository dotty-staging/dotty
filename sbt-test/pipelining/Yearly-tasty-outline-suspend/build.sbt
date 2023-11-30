// early out is a jar
lazy val a = project.in(file("a"))
  .settings(
    scalacOptions ++= Seq(
      "-Yexperimental-outline", "-Ymax-parallelism:1",
      "-Ycheck:all",
    )
  )
  .settings(
    fork := true, // correct runtime error handling requires forking
  )
