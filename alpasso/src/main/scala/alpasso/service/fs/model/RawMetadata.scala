package alpasso.service.fs.model

import cats.*
import cats.syntax.all.*

import alpasso.common.Converter
import alpasso.core.model.*

import io.circe.*
import io.circe.syntax.*

opaque type RawMetadata = Map[String, String]

object RawMetadata:

  given Converter[RawMetadata, SecretMetadata] = SecretMetadata.from

  def from(meta: SecretMetadata): RawMetadata =
    of(meta.asMap)

  def fromString(raw: String): Either[Exception, RawMetadata] =
    if raw.nonEmpty then parser.parse(raw).flatMap(_.as[Map[String, String]])
    else RawMetadata.empty.asRight

  extension (m: RawMetadata) def rawString: String = Printer.noSpaces.print(m.asJson)

  def of(kv: Map[String, String]): RawMetadata = kv

  val empty: RawMetadata = Map.empty
