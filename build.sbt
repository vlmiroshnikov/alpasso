
ThisBuild / scalaVersion := Versions.scala

lazy val core = project
  .in(file("core"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.bouncy ++ Deps.fs2
  )

lazy val alpasso = project
  .in(file("alpasso"))
  .dependsOn(core)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.scopt ++ Deps.jgit ++ Deps.glass ++ Deps.circe
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
