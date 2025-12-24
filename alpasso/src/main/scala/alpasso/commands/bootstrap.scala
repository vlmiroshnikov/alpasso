package alpasso.commands

import java.nio.file.Path

import cats.effect.*

import alpasso.infrastructure.cypher.CypherAlg
import alpasso.infrastructure.filesystem.{ PersistentModels, RepositoryProvisioner }
import alpasso.presentation.StorageView
import alpasso.shared.errors.*
import alpasso.shared.models.{ Result, SemVer }
import alpasso.shared.syntax.*

def bootstrap[F[_]: { Sync }](
    repoDir: Path,
    version: SemVer,
    cypher: CypherAlg): F[Result[StorageView]] =
  val provisioner = RepositoryProvisioner.make(repoDir)
  val config      = PersistentModels.RepositoryMetaConfig(version, cypher)
  provisioner.provision(config).liftE[Err].map(_ => StorageView(repoDir, cypher)).value
