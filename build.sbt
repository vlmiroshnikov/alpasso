
ThisBuild / scalaVersion := Versions.scala

lazy val core = project
  .in(file("core"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.bouncy ++ Deps.fs2
  )

lazy val cypherd = project
  .in(file("cypherd"))
  .dependsOn(core)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.glass ++ Deps.circe ++ Deps.bouncy ++ Deps.tapir ++ Deps.blaze ++ Deps.logstage
  )

lazy val alpasso = project
  .in(file("alpasso"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    Compile / mainClass := Some("alpasso.cli.CliApp"),
    Compile / discoveredMainClasses  := Seq()
  )
  .dependsOn(core, cypherd)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.scopt ++ Deps.jgit ++ Deps.glass ++ Deps.circe ++ Deps.logstage
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
