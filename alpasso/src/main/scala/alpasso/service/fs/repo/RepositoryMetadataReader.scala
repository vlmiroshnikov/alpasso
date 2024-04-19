package alpasso.service.fs.repo

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import alpasso.service.fs.repo.model.{CryptoAlg, RepositoryMeta}
import alpasso.shared.SemVer
import io.circe.*
import io.circe.derivation.*
import io.circe.syntax.given
import logstage.LogIO

case class Config(current: Path)

object model:
  given Configuration = Configuration.default.withSnakeCaseMemberNames

  enum CryptoAlg:
    case Gpg(fingerprint: String)
    case None

  case class RepositoryMeta(version: SemVer, cryptoAlg: CryptoAlg) derives ConfiguredCodec

type Logger[F[_]] = LogIO[F]

trait RepositoryMetadataReader[F[_]]:
  def read: F[Either[RepoMetaErr, RepositoryMeta]]

enum RepoMetaIntErr:
  case AlreadyExists

trait RepositoryMetadataProvisioner[F[_]]: // todo generalise provisioner
  def build(version: SemVer, cryptoAlg: CryptoAlg): F[Either[RepoMetaIntErr, RepositoryMeta]]

object RepositoryMetadataProvisioner:
  import StandardOpenOption.*

  val repoMetadataFile: String = ".repo"

  def make[F[_]: Logger](
      repoDir: Path
    )(using
      F: Sync[F]): RepositoryMetadataProvisioner[F] = new RepositoryMetadataProvisioner[F]:
    private val fullPath = repoDir.resolve(repoMetadataFile)
    import F.blocking

    override def build(version: SemVer, cryptoAlg: CryptoAlg): F[Either[RepoMetaIntErr, RepositoryMeta]] =
      blocking(Files.exists(fullPath)).flatMap { exists =>
        if exists then RepoMetaIntErr.AlreadyExists.asLeft.pure[F]
        else {
          val context = RepositoryMeta(version, cryptoAlg)
          blocking(Files.writeString(fullPath, context.asJson.noSpaces, CREATE, WRITE)) *> context
            .asRight
            .pure[F]
        }
      }

enum RepoMetaErr:
  case NotInitialized, InvalidFormat

object RepositoryMetadataReader:

  def make[F[_]: Logger](
      repoDir: Path
    )(using
      F: Sync[F]): RepositoryMetadataReader[F] = new RepositoryMetadataReader[F]:
    private val fullPath = repoDir.resolve(".repo")
    import F.blocking

    def read: F[Either[RepoMetaErr, RepositoryMeta]] =
      blocking(Files.exists(fullPath)).flatMap { exists =>
        if !exists then RepoMetaErr.NotInitialized.asLeft.pure[F]
        else {
          for
            raw <- blocking(Files.readString(fullPath))
            ctx <- blocking(parser.parse(raw).flatMap(_.as[RepositoryMeta]))
          yield ctx.leftMap(_ => RepoMetaErr.InvalidFormat)
        }
      }
