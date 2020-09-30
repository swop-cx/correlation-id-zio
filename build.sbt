import Dependencies._

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    inThisBuild(
      List(
        organization := "cx.swop",
        scalaVersion := "2.13.3",
        version := git.gitHeadCommit.value.getOrElse("0.1.0-SNAPSHOT"),
        buildInfoKeys := Seq[BuildInfoKey](version),
        buildInfoPackage := "cx.swop.correlation",
        scalacOptions ++= ScalacOptions.opts,
        scalacOptions in (Compile, console) --= ScalacOptions.excludeInConsoleAndCompile,
        addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
        shellPrompt := Prompt.format(scala.Console.BLUE)
      )
    ),
    name := "correlation-id-zio",
    maintainer := "hello@swop.cx",
    libraryDependencies ++= runtimeDeps ++ testDeps
  )
