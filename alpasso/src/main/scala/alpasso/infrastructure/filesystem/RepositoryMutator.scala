package alpasso.infrastructure.filesystem

import java.nio.file.StandardOpenOption.*
import java.nio.file.{ Files, OpenOption, Path }

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*

import alpasso.domain.SecretName
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.GitRepo
import alpasso.shared.syntax.*

import tofu.higherKind.*
import tofu.higherKind.Mid.*

trait RepositoryMutator[F[_]] derives ApplyK:

  def create(
      name: SecretName,
      payload: RawSecretData,
      meta: RawMetadata): F[Result[Locations]]

  def update(
      name: SecretName,
      payload: RawSecretData,
      meta: RawMetadata): F[Result[Locations]]
  def remove(name: SecretName): F[Result[Locations]]

object RepositoryMutator:

  def make[F[_]: { Async }](config: RepositoryConfiguration): RepositoryMutator[F] =
    val gitted: RepositoryMutator[Mid[F, *]] = Gitted[F](config.repoDir)
    gitted attach Impl[F](config.repoDir)

  class Impl[F[_]: { Sync as F }](repoDir: Path) extends RepositoryMutator[F] {

    import java.nio.file.Files.*

    import F.blocking

    private val CreateOps: List[OpenOption] = List(CREATE_NEW, WRITE)
    private val UpdateOps: List[OpenOption] = List(CREATE, TRUNCATE_EXISTING, WRITE)

    override def remove(name: SecretName): F[Result[Locations]] =
      val path = repoDir.resolve(name.asPath)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(payloadPath)).flatMap { rootExists =>
        if !rootExists then RepositoryErr.NotFound(name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.deleteIfExists(metaPath))
            _ <- blocking(Files.deleteIfExists(payloadPath))
            _ <- blocking(Files.deleteIfExists(path))
          yield Locations(payloadPath, metaPath).asRight
      }

    override def create(
        name: SecretName,
        payload: RawSecretData,
        meta: RawMetadata): F[Result[Locations]] =
      val path = repoDir.resolve(name.asPath)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(exists(path) && exists(payloadPath)).flatMap { rootExists =>
        if rootExists then RepositoryErr.AlreadyExists(name).asLeft.pure[F]
        else
          for
            _ <- blocking(createDirectories(path))
            _ <- blocking(write(payloadPath, payload.byteArray, CreateOps*))
            _ <- blocking(writeString(metaPath, meta.rawString, CreateOps*))
          yield Locations(payloadPath, metaPath).asRight
      }

    override def update(
        name: SecretName,
        payload: RawSecretData,
        metadata: RawMetadata): F[Result[Locations]] =
      val path = repoDir.resolve(name.asPath)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(exists(path) && exists(metaPath)).flatMap { exists =>
        if !exists then RepositoryErr.Corrupted(name).asLeft.pure[F]
        else
          for
            _ <- blocking(write(payloadPath, payload.byteArray, UpdateOps*))
            _ <- blocking(writeString(metaPath, metadata.rawString, UpdateOps*))
          yield Locations(payloadPath, metaPath).asRight
      }
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryMutator[Mid[F, *]] {

    override def create(
        name: SecretName,
        payload: RawSecretData,
        meta: RawMetadata): Mid[F, Result[Locations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Add secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }

    override def update(
        name: SecretName,
        payload: RawSecretData,
        meta: RawMetadata): Mid[F, Result[Locations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Update secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }

    override def remove(name: SecretName): Mid[F, Result[Locations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Remove secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.removeFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }
  }
