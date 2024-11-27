ThisBuild / scalaVersion       := Versions.scala
ThisBuild / evictionErrorLevel := Level.Warn

lazy val core = project
  .in(file("core"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.fs2 ++ Deps.tagless ++ Deps.evo_circe
  )

lazy val shared = project
  .in(file("shared"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.evo_circe ++ Deps.circe
  )

//lazy val cypherd = project
//  .in(file("cypherd"))
//  .dependsOn(core, shared)
//  .settings(Settings.common)
//  .settings(
//    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.glass ++ Deps.circe ++ Deps.bouncy ++ Deps.tapir ++ Deps.blaze ++ Deps.logstage
//  )

lazy val alpasso = project
  .in(file("alpasso"))
  .enablePlugins(NativeImagePlugin)
  .settings(
    Compile / mainClass             := Some("alpasso.cli.CliApp"),
    Compile / discoveredMainClasses := Seq()
  )
  .settings(
    nativeImageVersion := "22.3.1",
    nativeImageJvm     := "graalvm-java19",
   // nativeImageOptions += s"-H:ReflectionConfigurationFiles=${target.value / "native-image-configs" / "reflect-config.json"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${target.value / "native-image-configs"}",
    nativeImageOptions += s"-H:ConfigurationFileDirectories=${(Compile / resourceDirectory).value / "native-image-configs"}",
    nativeImageOptions += "-H:+JNI",
    nativeImageOptions ++= Seq(
      "--enable-url-protocols=https",
      "-H:+TraceSecurityServices",
      "-H:+ReportExceptionStackTraces",
      "--no-fallback"
    )
  )
  .dependsOn(core, shared)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Deps.cats ++ Deps.catsEffect ++ Deps.scopt ++ Deps.jgit ++ Deps.glass ++ Deps.circe ++ Deps.logstage ++ Deps.tagless ++ Deps.tofu
  )

lazy val root = project
  .in(file("."))
  .settings(Settings.common)
  .aggregate(alpasso)
