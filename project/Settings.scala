import sbt.Keys.*
import sbt.*

object Versions {
  val scala      = "3.3.1"
  val cats       = "2.10.0"
  val catsEffect = "3.5.1"
  val circe      = "0.14.6"
  val logstage   = "1.2.5"
}

object Settings {

  lazy val common = Seq(
    version        := Versions.scala,
    scalacOptions ++= Seq("-deprecation", "-new-syntax")
  )
}

object Deps {
  lazy val cats       = Seq("org.typelevel" %% "cats-core").map(_ % Versions.cats)
  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect").map(_ % Versions.catsEffect)
  lazy val bouncy     = Seq("org.bouncycastle" % "bcprov-jdk18on", "org.bouncycastle" % "bcpg-jdk18on").map(_  % "1.76")
  lazy val jgit       = Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "6.6.1.202309021850-r")
  lazy val fs2        = Seq("co.fs2" %% "fs2-core", "co.fs2" %% "fs2-io").map( _ % "3.10.0")
  lazy val scopt      = Seq("com.github.scopt" %% "scopt" % "4.1.0")
  lazy val glass      = Seq("tf.tofu" %% "glass-core" % "0.3.0")
  lazy val circe      = Seq("io.circe" %% "circe-core", "io.circe" %% "circe-parser").map(_ % Versions.circe)
  lazy val decline    = Seq("com.monovore" %% "decline" % "2.4.1")

  lazy val tapir      = Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % "1.9.9",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.10.3",
      "com.softwaremill.sttp.tapir" %% "tapir-redoc-bundle" % "1.9.9",
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % "1.10.3"
  )

  lazy val logstage  = Seq(
    "io.7mind.izumi" %% "logstage-core",
    "io.7mind.izumi" %% "logstage-rendering-circe",
    // Router from Slf4j to LogStage
    "io.7mind.izumi" %% "logstage-adapter-slf4j",
    // Router from LogStage to Slf4J
 //   "io.7mind.izumi" %% "logstage-sink-slf4j ",
  ).map( _  %  Versions.logstage)

  lazy val blaze  = Seq( "org.http4s" %% "http4s-blaze-server" % "0.23.16")
}
