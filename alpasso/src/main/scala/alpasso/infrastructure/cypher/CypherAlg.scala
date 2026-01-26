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
  case MasterKey

object CypherAlg:
  given Show[CypherAlg] = Show.show {
    case CypherAlg.Gpg(recipient) => s"GPG: [ ${recipient} ]"
    case CypherAlg.MasterKey      => "Master Key"
  }

  given Encoder[CypherAlg] = Encoder.encodeJson.contramap {
    case CypherAlg.Gpg(recipient) =>
      Json.obj("type" -> "gpg".asJson, "fingerprint" -> recipient.asJson)
    case CypherAlg.MasterKey =>
      Json.obj("type" -> "master-key".asJson)
  }

  given Decoder[CypherAlg] = Decoder.instance { hcursor =>
    for
      t <- hcursor.get[String]("type")
      result <- t match
                  case "gpg" =>
                    hcursor.get[Recipient]("fingerprint").map(CypherAlg.Gpg(_))
                  case "master-key" =>
                    Right(CypherAlg.MasterKey).pure[Decoder.Result]
                  case _ =>
                    Left(DecodingFailure(s"Unknown cipher algorithm: $t", hcursor.history))
    yield result
  }
