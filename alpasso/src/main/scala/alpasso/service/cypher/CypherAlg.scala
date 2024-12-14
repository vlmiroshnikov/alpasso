package alpasso.service.cypher

import cats.Show

import evo.derivation.*
import evo.derivation.circe.EvoCodec
import evo.derivation.config.Config

@Discriminator("type")
@SnakeCase
enum CypherAlg derives Config, EvoCodec:
  case Gpg(fingerprint: String)

object CypherAlg:
  given Show[CypherAlg] = Show.show { case CypherAlg.Gpg(fg) => s"GPG: [ ${fg} ]" }
