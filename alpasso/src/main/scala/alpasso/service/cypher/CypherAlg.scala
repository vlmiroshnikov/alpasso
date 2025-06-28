package alpasso.service.cypher

import cats.Show

import io.circe.*
import io.circe.syntax.*

enum CypherAlg:
  case Gpg(fingerprint: String)

object CypherAlg:
  given Show[CypherAlg] = Show.show { case CypherAlg.Gpg(fg) => s"GPG: [ ${fg} ]" }

  given Encoder[CypherAlg] = Encoder.encodeJson.contramap {
    case CypherAlg.Gpg(fg) => Json.obj("type" -> "gpg".asJson, "fingerprint" -> fg.asJson)
  }

  given Decoder[CypherAlg] = Decoder.instance { hcursor =>
    for
      t  <- hcursor.get[String]("type")
      fg <- hcursor.get[String]("fingerprint")
    yield t match
      case "gpg" => CypherAlg.Gpg(fg)
  }
