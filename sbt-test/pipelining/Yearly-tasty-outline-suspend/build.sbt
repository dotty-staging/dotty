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

lazy val b = project.in(file("b"))
  .settings(
    scalacOptions ++= Seq(
      "-Yexperimental-outline", "-Ymax-parallelism:2", // 2 source files, guaranteed to be in parallel runs
      "-Ycheck:all",
    )
  )
  .settings(
    fork := true, // correct runtime error handling requires forking
  )
