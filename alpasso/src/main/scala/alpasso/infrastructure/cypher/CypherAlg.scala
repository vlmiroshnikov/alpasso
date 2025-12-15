package alpasso.infrastructure.cypher

import cats.Show

import io.circe.*
import io.circe.syntax.*

opaque type Recipient <: String = String

object Recipient:
  def hex(value: String): Recipient = value

  given Encoder[Recipient] = Encoder.encodeString.contramap(identity)
  given Decoder[Recipient] = Decoder.decodeString.map(Recipient.hex)

enum CypherAlg:
  case Gpg(recipient: Recipient)

object CypherAlg:
  given Show[CypherAlg] = Show.show { case CypherAlg.Gpg(recipient) => s"GPG: [ ${recipient} ]" }

  given Encoder[CypherAlg] = Encoder.encodeJson.contramap {
    case CypherAlg.Gpg(recipient) =>
      Json.obj("type" -> "gpg".asJson, "fingerprint" -> recipient.asJson)
  }

  given Decoder[CypherAlg] = Decoder.instance { hcursor =>
    for
      t  <- hcursor.get[String]("type")
      fg <- hcursor.get[Recipient]("fingerprint")
    yield t match
      case "gpg" => CypherAlg.Gpg(fg) // todo fix match
  }
