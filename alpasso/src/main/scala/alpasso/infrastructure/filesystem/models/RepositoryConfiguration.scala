package alpasso.infrastructure.filesystem.models

import java.nio.file.Path

import alpasso.infrastructure.cypher.CypherAlg
import alpasso.shared.models.SemVer

case class RepositoryConfiguration(repoDir: RepoRootDir, version: SemVer, cypherAlg: CypherAlg)
