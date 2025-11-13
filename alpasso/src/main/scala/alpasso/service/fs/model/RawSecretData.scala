package alpasso.service.fs.model

import cats.Show

import alpasso.common.Converter
import alpasso.core.model.SecretPayload

opaque type RawSecretData = Array[Byte]

object RawSecretData:
  given Converter[RawSecretData, SecretPayload] = SecretPayload.fromRaw

  def fromRaw(bytes: Array[Byte]): RawSecretData = bytes

  given Show[RawSecretData] = Show.show(v => BigInt(v).toString(18))

  extension (p: RawSecretData) def byteArray: Array[Byte] = p
