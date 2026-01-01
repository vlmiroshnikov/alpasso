package alpasso.infrastructure.filesystem

import java.nio.file.*

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import FileEffects.*
import io.circe.*
import PersistentModels.*

trait RepositoryConfigReader[F[_]]:
  def read(path: Path): F[Either[RepositoryConfigErr, RepositoryMetaConfig]]

enum RepositoryConfigErr:
  case NotInitialized(path: Path)
  case Corrupted(path: Path)

object RepositoryConfigReader:

  def make[F[_]: {Sync as S}]: RepositoryConfigReader[F] = (repoDir: Path) =>
    import S.blocking

    val fullPath = repoDir.resolve(SessionProvisioner.repoMetadataFile)

    pathExists(fullPath).flatMap { exists =>
      if !exists then RepositoryConfigErr.NotInitialized(fullPath).asLeft.pure[F]
      else
        for
          raw <- readString(fullPath)
          ctx <- blocking(parser.parse(raw).flatMap(_.as[RepositoryMetaConfig]))
        yield ctx.leftMap(_ => RepositoryConfigErr.Corrupted(fullPath))
    }
