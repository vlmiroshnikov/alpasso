package alpasso.service.fs.model

import java.nio.file.Path

import cats.Show

opaque type RawSecretData = Array[Byte]

object RawSecretData:
  def from(bytes: Array[Byte]): RawSecretData = bytes

  given Show[RawSecretData] = Show.show(v => BigInt(v).toString(18))

  extension (p: RawSecretData) def byteArray: Array[Byte] = p
