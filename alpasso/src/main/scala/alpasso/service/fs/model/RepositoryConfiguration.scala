package alpasso.service.fs.model

import java.nio.file.Path

import alpasso.common.SemVer
import alpasso.service.cypher.CypherAlg

case class RepositoryConfiguration(repoDir: Path, version: SemVer, cypherAlg: CypherAlg)
