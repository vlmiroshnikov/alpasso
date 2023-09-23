package pass.service.fs.model

import cats.*
import cats.syntax.all.*

import io.circe.*
import io.circe.syntax.*

opaque type Metadata = Map[String, String]

object Metadata:

  def fromString(raw: String): Either[Exception, Metadata] =
    if raw.nonEmpty then parser.parse(raw).flatMap(_.as[Map[String, String]])
    else Metadata.empty.asRight

  extension (m: Metadata) def rawString: String = Printer.spaces2.print(m.asJson)

  given Show[Metadata] =
    Show.show(v => v.toList.map((a, b) => s"$a=$b").mkString(","))
  def of(kv: Map[String, String]): Metadata = kv

  val empty: Metadata = Map.empty
