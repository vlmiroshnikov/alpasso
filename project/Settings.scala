import sbt.*
import sbt.Keys.*

object Versions {
  val scala      = "3.4.2"
  val cats       = "2.10.0"
  val catsEffect = "3.5.1"
  val circe      = "0.14.6"
  val logstage   = "1.2.5"
}

object Settings {

  lazy val common = Seq(
    version := Versions.scala,
    scalacOptions ++= Seq("-deprecation",
                          "-new-syntax",
                          "-Ykind-projector",
                          "-experimental",
                          "-Wconf:msg=eta-expanded even though:silent"
    )
  )
}

object Deps {
  lazy val cats       = Seq("org.typelevel" %% "cats-core").map(_ % Versions.cats)
  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect").map(_ % Versions.catsEffect)
  lazy val tagless    = Seq("org.typelevel" %% "cats-tagless-core" % "0.16.2")

  lazy val jgit  = Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "6.5.0.202303070854-r")
  lazy val fs2   = Seq("co.fs2" %% "fs2-core", "co.fs2" %% "fs2-io").map(_ % "3.10.0")
  lazy val glass = Seq("tf.tofu" %% "glass-core" % "0.3.0")

  lazy val circe =
    Seq("io.circe" %% "circe-core", "io.circe" %% "circe-parser").map(_ % Versions.circe)
  lazy val evo_circe = Seq("com.evolution" % "derivation-circe_3" % "0.2.0")
  lazy val decline   = Seq("com.monovore" %% "decline" % "2.4.1")
  lazy val tofu      = Seq("tf.tofu" %% "tofu-core-higher-kind" % "0.13.6")

  lazy val logstage = Seq(
    "io.7mind.izumi" %% "logstage-core",
    "io.7mind.izumi" %% "logstage-rendering-circe",
    // Router from Slf4j to LogStage
    "io.7mind.izumi" %% "logstage-adapter-slf4j"
    // Router from LogStage to Slf4J
    //   "io.7mind.izumi" %% "logstage-sink-slf4j ",
  ).map(_ % Versions.logstage)
}
