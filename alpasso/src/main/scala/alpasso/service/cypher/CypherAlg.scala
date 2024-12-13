package alpasso.service.cypher

import evo.derivation.*
import evo.derivation.circe.EvoCodec
import evo.derivation.config.Config

@Discriminator("type")
@SnakeCase
enum CypherAlg derives Config, EvoCodec:
  case Gpg(fingerprint: String)
