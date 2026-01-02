ThisBuild / scalaVersion       := V.scala
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / version            := "0.0.1"

lazy val alpasso = project
  .in(file("alpasso"))
  .enablePlugins(NativeImagePlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    Compile / mainClass             := Some("alpasso.AlpassoApp"),
    Compile / discoveredMainClasses := Seq()
  )
  .settings(
    nativeImageVersion := V.nativeImage,
    nativeImageJvm     := V.graalvm ,
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
    buildInfoPackage := "alpasso.shared.models"
  )
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.infra ++ Deps.jgit ++ Deps.circe ++ Deps.decline ++ Deps.logger ++ Deps.munit ++ Deps.weaver
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
