import sbt.*
import sbt.Keys.*

object Versions {
  val scala      = "3.7.4"
  val cats       = "2.13.0"
  val catsEffect = "3.6.1"
  val circe      = "0.14.13"
}

object Settings {

  lazy val common = Seq(
    version := Versions.scala,
    scalacOptions ++= Seq("-deprecation",
                          "-new-syntax",
                          "-Xkind-projector",
                          "-experimental",
                          "-Wconf:msg=eta-expanded even though:silent"
    )
  )
}

object Deps {
  lazy val cats       = Seq("org.typelevel" %% "cats-core").map(_ % Versions.cats)
  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect").map(_ % Versions.catsEffect)
  lazy val tagless    = Seq("org.typelevel" %% "cats-tagless-core" % "0.16.4")
  lazy val glass      = Seq("tf.tofu" %% "glass-core" % "0.3.0")
  lazy val tofu       = Seq("tf.tofu" %% "tofu-core-higher-kind" % "0.14.0")

  lazy val infra      = cats ++ catsEffect ++ tagless ++ glass ++ tofu

  lazy val jgit       = Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "6.5.0.202303070854-r")

  lazy val circe     = Seq("io.circe" %% "circe-core", "io.circe" %% "circe-parser").map(_ % Versions.circe)
  lazy val decline   = Seq("com.monovore" %% "decline" % "2.5.0")
  lazy val logger    = Seq("org.slf4j" % "slf4j-nop" % "2.0.17")
  lazy val munit     = Seq("org.scalameta" %% "munit" % "1.2.1" % Test)
  lazy val weaver    = Seq("org.typelevel" %% "weaver-cats" % "0.11.3")
}
