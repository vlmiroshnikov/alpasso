import sbt.*
import sbt.Keys.*

object V {
  val scala       = "3.7.4"
  val cats        = "2.13.0"
  val catsEffect  = "3.6.3"
  val tagless     = "0.16.4"
  val circe       = "0.14.13"
  val glass       = "0.3.0"
  val tofu        = "0.14.0"
  val decline     = "2.5.0"
  val munit       = "1.2.1"
  val weaver      = "0.11.3"
  val nativeImage = "22.3.1"
  val jgit        = "6.5.0.202303070854-r"
  val graalvm     = "graalvm-java19"
  val scalapb     = "0.11.15"
  val grpc        = "1.60.1"
  val protobuf    = "3.25.3"
}

object Settings {

  lazy val common = Seq(
    version        := V.scala,
    scalacOptions ++= Seq("-deprecation",
                          "-new-syntax",
                          "-Xkind-projector",
                          "-experimental",
                          "-Wconf:msg=eta-expanded even though:silent"
    )
  )
}

object Deps {
  lazy val cats       = Seq("org.typelevel" %% "cats-core").map(_ % V.cats)
  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect").map(_ % V.catsEffect)
  lazy val tagless    = Seq("org.typelevel" %% "cats-tagless-core" % V.tagless)
  lazy val glass      = Seq("tf.tofu" %% "glass-core" % V.glass)
  lazy val tofu       = Seq("tf.tofu" %% "tofu-core-higher-kind" % V.tofu)

  lazy val infra = cats ++ catsEffect ++ tagless ++ glass ++ tofu

  lazy val jgit = Seq("org.eclipse.jgit" % "org.eclipse.jgit" % V.jgit)

  lazy val circe   = Seq("io.circe" %% "circe-core", "io.circe" %% "circe-parser").map(_ % V.circe)
  lazy val decline = Seq("com.monovore" %% "decline" % V.decline)
  lazy val logger  = Seq("org.slf4j" % "slf4j-nop" % "2.0.17")
  lazy val munit   = Seq("org.scalameta" %% "munit" % V.munit % Test)
  lazy val weaver  = Seq("org.typelevel" %% "weaver-cats" % V.weaver % Test)

  lazy val grpc = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime" % V.scalapb,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % V.scalapb,
    "io.grpc" % "grpc-netty-shaded" % V.grpc,
    "io.grpc" % "grpc-services" % V.grpc,
    "org.typelevel" %% "fs2-grpc-runtime" % "2.7.4"
  )
}
