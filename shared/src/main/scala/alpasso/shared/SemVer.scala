package alpasso.shared

case class SemVer(major: Int, minor: Int, patch: Int)

object SemVer:
  val zero: SemVer = SemVer(0, 0, 0)

  given Ordering[SemVer] =
    (x: SemVer, y: SemVer) =>
      val majorRes = x.major.compare(y.major)
      val minorRes = x.minor.compare(y.minor)
      val patchRes = x.patch.compare(y.patch)

      (majorRes, minorRes, patchRes) match
        case (0, 0, _) => patchRes
        case (0, _, _) => minorRes
        case (_, _, _) => majorRes
