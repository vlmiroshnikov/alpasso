
ThisBuild / scalaVersion := Versions.scala

lazy val core = project
  .in(file("core"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.bouncy ++ Deps.fs2
  )

lazy val pass = project
  .in(file("pass"))
  .dependsOn(core)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.scopt
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(pass)
