lazy val a = project.in(file("a"))
  .settings(
    compileOrder := CompileOrder.Mixed, // ensure we send java sources to Scala compiler
    scalacOptions += "-Yjava-tasty", // enable pickling of java signatures
    scalacOptions ++= Seq("-Yearly-tasty-output", ((ThisBuild / baseDirectory).value / "a-enum-java-tasty.jar").toString),
    scalacOptions += "-Ycheck:all",
    Compile / classDirectory := ((ThisBuild / baseDirectory).value / "a-enum-classes"), // send classfiles to a different directory
  )


lazy val b = project.in(file("b"))
  .settings(
    Compile / unmanagedClasspath := Seq(Attributed.blank((ThisBuild / baseDirectory).value / "a-enum-java-tasty.jar")),
    scalacOptions += "-Ycheck:all",
  )
  .settings(
    fork := true, // we have to fork the JVM if we actually want to run the code with correct failure semantics
    Runtime / unmanagedClasspath += Attributed.blank((ThisBuild / baseDirectory).value / "a-enum-classes"), // make sure the java classes are visible at runtime
  )
