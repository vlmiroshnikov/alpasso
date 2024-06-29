
ThisBuild / scalaVersion := Versions.scala
ThisBuild / evictionErrorLevel := Level.Warn


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
    libraryDependencies ++= Deps.cats ++ Deps.evo_circe ++ Deps.circe
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
  .enablePlugins(NativeImagePlugin)
  .settings(
    Compile / mainClass := Some("alpasso.cli.CliApp"),
    Compile / discoveredMainClasses  := Seq(),
  ).settings(
    nativeImageVersion := "22.3.1",
    nativeImageJvm := "graalvm-java19",
    nativeImageOptions ++= Seq(
      s"-H:ReflectionConfigurationFiles=${(Compile / resourceDirectory).value / "reflect-config.json"}",
      "-H:AdditionalSecurityProviders=org.bouncycastle.jce.provider.BouncyCastleProvider",
      "--initialize-at-build-time=org.bouncycastle.crypto.prng.SP800SecureRandom",
      "--initialize-at-run-time=org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV",
      "--initialize-at-run-time=org.bouncycastle.jcajce.provider.drbg.DRBG$Default",
      "--enable-url-protocols=https",
      "-H:+TraceSecurityServices",
      "-H:+ReportExceptionStackTraces",
      "--no-fallback"
    )
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
