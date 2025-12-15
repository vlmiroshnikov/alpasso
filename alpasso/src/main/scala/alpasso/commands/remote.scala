package alpasso.commands

import cats.effect.Sync

import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.GitRepo
import alpasso.shared.errors.*
import alpasso.shared.models.*
import alpasso.shared.syntax.*

def setupRemote[F[_]: Sync](
    name: String,
    url: String
  )(configuration: RepositoryConfiguration): F[Result[Unit]] =
  GitRepo.openExists(configuration.repoDir).use(_.addRemote(name, url).liftE[Err].value)

def syncRemote[F[_]: Sync](configuration: RepositoryConfiguration): F[Result[Unit]] =
  GitRepo.openExists(configuration.repoDir).use { git =>
    val result = for
      _ <- git.pullRemote().liftE[Err]
      _ <- git.pushToRemote().liftE[Err]
    yield ()

    result.value
  }
