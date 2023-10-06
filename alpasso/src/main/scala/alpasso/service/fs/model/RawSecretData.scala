package alpasso.service.fs.model

opaque type RawSecretData = Array[Byte]
extension (p: RawSecretData) def byteArray: Array[Byte] = p

object RawSecretData:
  def from(bytes: Array[Byte]): RawSecretData = bytes
