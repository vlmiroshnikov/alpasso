package alpasso.core.model

import java.nio.charset.Charset

import cats.Show
import cats.data.{ Validated, ValidatedNel }
import cats.syntax.option.*

opaque type SecretPayload = Array[Byte]

object SecretPayload:
  private val utf8Charset = Charset.forName("UTF-8")

  val empty: SecretPayload = Array[Byte]()

  def fromRaw(data: Array[Byte]): SecretPayload = data
  def fromString(data: String): SecretPayload   = data.getBytes(utf8Charset)

  extension (s: SecretPayload) def rawData: Array[Byte] = s

opaque type SecretMetadata = Map[String, String]

object SecretMetadata:
  given Show[SecretMetadata] = Show.show(_.mkString(","))

  extension (sm: SecretMetadata) def asMap: Map[String, String] = sm

  def from(m: Map[String, String]): SecretMetadata = m

  def fromRaw(s: String): ValidatedNel[String, SecretMetadata] =
    val items = s.split(",").toList
    val tags = items.flatMap { m =>
      m.split('=').toList match
        case head :: tail :: Nil => (head, tail).some
        case _                   => None
    }

    Validated.valid(tags.toMap)
