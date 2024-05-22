
ThisBuild / scalaVersion := Versions.scala

lazy val core = project
  .in(file("core"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.bouncy ++ Deps.fs2 ++ Deps.tagless ++ Deps.evo_circe
  )

lazy val shared = project
  .in(file("shared"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats
  )

lazy val cypherd = project
  .in(file("cypherd"))
  .dependsOn(core, shared)
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
  .dependsOn(core, cypherd, shared)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.scopt ++ Deps.jgit ++ Deps.glass ++ Deps.circe ++ Deps.logstage ++ Deps.tagless ++ Deps.tofu
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
