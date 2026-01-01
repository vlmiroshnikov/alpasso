package alpasso.infrastructure.filesystem.models

import cats.{Eq, Show}
import alpasso.domain.SecretPayload
import alpasso.shared.models.Converter
import cats.syntax.all.*

opaque type RawSecretData = Array[Byte]

object RawSecretData:
  extension (p: RawSecretData) def byteArray: Array[Byte] = p

  val empty: RawSecretData                      = RawSecretData.fromBytes(Array.emptyByteArray)
  given Converter[RawSecretData, SecretPayload] = SecretPayload.fromRaw

  def fromBytes(bytes: Array[Byte]): RawSecretData = bytes

  given Show[RawSecretData] = Show.show(v => BigInt(v).toString(18))
