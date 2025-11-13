package alpasso.cmdline.view

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import scala.util.Try

import cats.*

import alpasso.common.Converter
import alpasso.core.model.SecretMetadata

import Console.*

case class SimpleMetadataView(
    expiredAt: Option[ExpiredTag],
    ref: Option[String],
    tags: Map[String, String])

object SimpleMetadataView:
  private val ExpiredAtTag = "expiredAt"
  private val RefTag       = "ref"

  given Converter[SecretMetadata, SimpleMetadataView] = sm =>
    val m    = sm.asMap
    val e    = m.get(ExpiredAtTag).flatMap(ExpiredTag.of)
    val r    = m.get(RefTag)
    val tags = m.view.filterKeys(k => !(ExpiredAtTag != k || RefTag != k)).toMap

    SimpleMetadataView(e, r, tags)

  given Show[SimpleMetadataView] = Show.show { m =>
    val s    = m.tags.map((k, v) => s"$k=$v")
    val exp  = m.expiredAt.map(e => s"expiredAt=$e")
    val ref  = m.ref.map(e => s"ref=${GREEN}$e${RESET}")
    val list = List(exp, ref).flatten ++ s
    s"${YELLOW} ${list.mkString(", ")} ${RESET}"
  }

opaque type ExpiredTag = LocalDate

object ExpiredTag:
  private val df = DateTimeFormatter.ISO_DATE

  def of(s: String): Option[ExpiredTag] =
    Try(df.parse(s, LocalDate.from)).toOption

  given Show[ExpiredTag] = Show.show(df.format(_))
