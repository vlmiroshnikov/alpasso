package alpasso.infrastructure.filesystem.models

import cats.Show

import alpasso.domain.SecretPayload
import alpasso.shared.models.Converter

opaque type RawSecretData = Array[Byte]
  extension (p: RawSecretData)
    def byteArray: Array[Byte] = p

object RawSecretData:
  
  val empty: RawSecretData = RawSecretData.fromRaw(Array.emptyByteArray)
  given Converter[RawSecretData, SecretPayload] = SecretPayload.fromRaw

  def fromRaw(bytes: Array[Byte]): RawSecretData = bytes

  given Show[RawSecretData] = Show.show(v => BigInt(v).toString(18))


