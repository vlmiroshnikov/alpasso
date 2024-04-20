package alpasso.service.fs.model

import cats.Show

opaque type RawSecretData = Array[Byte]
extension (p: RawSecretData) def byteArray: Array[Byte] = p

object RawSecretData:
  def from(bytes: Array[Byte]): RawSecretData = bytes

  given Show[RawSecretData] = Show.show(v=> BigInt(v).toString(18))
