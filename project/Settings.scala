import sbt.Keys.*
import sbt.*

object Versions {
  val scala      = "3.3.1"
  val cats       = "2.10.0"
  val catsEffect = "3.5.1"
  val circe      = "0.14.6"
}

object Settings {

  lazy val common = Seq(
    version        := Versions.scala,
    scalacOptions ++= Seq("-rewrite", "-indent", "-deprecation")
  )
}

object Deps {
  lazy val cats       = Seq("org.typelevel" %% "cats-core").map(_ % Versions.cats)
  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect").map(_ % Versions.catsEffect)
  lazy val bouncy     = Seq("org.bouncycastle" % "bcprov-jdk18on" % "1.76")
  lazy val jgit       = Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "6.6.1.202309021850-r")
  lazy val fs2        = Seq("co.fs2" %% "fs2-core" % "3.9.0", "co.fs2" %% "fs2-io" % "3.9.0")
  lazy val scopt      = Seq("com.github.scopt" %% "scopt" % "4.1.0")
  lazy val glass      = Seq("tf.tofu" %% "glass-core" % "0.2.2")
  lazy val circe      = Seq("io.circe" %% "circe-core", "io.circe" %% "circe-parser").map(_ % Versions.circe)
}
