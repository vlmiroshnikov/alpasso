package pass.service.fs.model

opaque type Payload = Array[Byte]
  extension (p: Payload)
    def byteArray: Array[Byte] = p

object Payload:
  def from(bytes: Array[Byte]): Payload = bytes

