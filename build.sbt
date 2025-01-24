ThisBuild / scalaVersion       := Versions.scala
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / version            := "0.0.1"

lazy val alpasso = project
  .in(file("alpasso"))
  .enablePlugins(NativeImagePlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    Compile / mainClass             := Some("alpasso.cli.CliApp"),
    Compile / discoveredMainClasses := Seq()
  )
  .settings(
    nativeImageVersion := "22.3.1",
    nativeImageJvm     := "graalvm-java19",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${target.value / "native-image-configs"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${(Compile / resourceDirectory).value / "native-image-configs"}",
    nativeImageOptions += "-H:+JNI",
    nativeImageOptions ++= Seq(
      "-H:+ReportExceptionStackTraces",
      "--no-fallback",
      "-H:-CheckToolchain"
    )
  )
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, ThisBuild / version, scalaVersion),
    buildInfoPackage := "alpasso.common.build"
  )
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.jgit ++ Deps.glass ++ Deps.circe ++ Deps.logstage ++ Deps.tagless ++ Deps.tofu ++ Deps.decline ++ Deps.evo_circe
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
