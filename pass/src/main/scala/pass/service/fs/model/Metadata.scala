package pass.service.fs.model

import cats.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*

opaque type Metadata = JsonObject


object Metadata:
  def fromString(raw: String): Either[Exception, Metadata] =
    parser.parse(raw).flatMap(_.as[JsonObject])

  extension (m: Metadata)
    def rawString: String = Printer.spaces2.print(m.toJson)

  def of(kv: Map[String, String]): Metadata = JsonObject.fromMap(kv.view.mapValues(_.asJson).toMap)

  val empty: Metadata = JsonObject.empty

