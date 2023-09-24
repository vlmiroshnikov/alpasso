package alpasso.core.model

import java.nio.charset.Charset

opaque type SecretPayload = Array[Byte]

object SecretPayload:
  private val utf8Charset                       = Charset.forName("UTF-8")
  def fromRaw(data: Array[Byte]): SecretPayload = data
  def fromString(data: String): SecretPayload   = data.getBytes(utf8Charset)

  extension (s: SecretPayload) def rawData: Array[Byte] = s
