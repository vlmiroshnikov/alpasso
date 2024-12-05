package alpasso.common

import scala.util.matching.Regex

import cats.*
import cats.syntax.all.*

import io.circe.{ Codec, Decoder, Encoder }

case class SemVer(major: Int, minor: Int, patch: Int)

object SemVer:
  val zero: SemVer = SemVer(0, 0, 0)

  given Show[SemVer]    = Show.show(v => s"${v.major}.${v.minor}.${v.patch}")
  given Encoder[SemVer] = Encoder.encodeString.contramap(v => s"${v.major}.${v.minor}.${v.patch}")

  private val parts = """(\d+)\.(\d+)\.(\d+)""".r

  given Decoder[SemVer] = Decoder.decodeString.emap {
    case parts(ma, mi, p) => SemVer(ma.toInt, mi.toInt, p.toInt).asRight
    case _                => "mismatch semver format".asLeft
  }

  given Ordering[SemVer] =
    (x: SemVer, y: SemVer) =>
      val majorRes = x.major.compare(y.major)
      val minorRes = x.minor.compare(y.minor)
      val patchRes = x.patch.compare(y.patch)

      (majorRes, minorRes, patchRes) match
        case (0, 0, _) => patchRes
        case (0, _, _) => minorRes
        case (_, _, _) => majorRes
