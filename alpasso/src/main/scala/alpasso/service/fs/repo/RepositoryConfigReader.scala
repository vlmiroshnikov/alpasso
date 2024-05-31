package alpasso.service.fs.repo

import java.nio.file.{ Files, Path, Paths, StandardOpenOption }

import scala.annotation.experimental

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*
import cats.tagless.derived.*
import cats.tagless.syntax.*

import alpasso.service.cypher.CypherService
import alpasso.service.fs.repo.model.{ CryptoAlg, RepositoryConfiguration, RepositoryMetaConfig }
import alpasso.service.git.GitRepo
import alpasso.shared.SemVer

import evo.derivation.*
import evo.derivation.circe.*
import evo.derivation.config.Config
import io.circe.*
import io.circe.derivation.*
import io.circe.syntax.given
import logstage.LogIO
import logstage.LogIO.log
import tofu.higherKind.*
import tofu.higherKind.Mid.*

case class RootPathConfig(current: Path)

object model:

  @Discriminator("type")
  @SnakeCase
  enum CryptoAlg derives Config, EvoCodec:
    case Gpg(fingerprint: String)
    case Raw

  case class RepositoryConfiguration(repoDir: Path, version: SemVer, cryptoAlg: CryptoAlg)

  @SnakeCase
  case class RepositoryMetaConfig(version: SemVer, cryptoAlg: CryptoAlg) derives Config, EvoCodec

type Logger[F[_]] = LogIO[F]

trait RepositoryConfigReader[F[_]]:
  def read: F[Either[RepoMetaErr, RepositoryMetaConfig]]

enum ProvisionErr:
  case AlreadyExists, Undefined

@experimental
trait Provisioner[F[_]] derives ApplyK:
  def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]]

@experimental
object RepositoryProvisioner:
  import StandardOpenOption.*

  val repoMetadataFile: String = ".repo"

  def make[F[_]: Logger: Async](repoDir: Path): Provisioner[F] =
    val alg = MetaProvisioner(repoDir)

    val gitted: Provisioner[Mid[F, *]] = GitProvisioner[F](repoDir)
    val logged: Provisioner[Mid[F, *]] = LoggingProvisioner[F]
    val cs: Provisioner[Mid[F, *]]     = CypherProvisioner[F]

    (cs |+| gitted |+| logged) attach alg

  class CypherProvisioner[F[_]: Async: Logger] extends Provisioner[Mid[F, *]] {

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] = {
      action =>
        (for
          cs <- EitherT(CypherService.make(config.cryptoAlg)).leftMap(_ => ProvisionErr.Undefined)
          _  <- EitherT.liftF(cs.encrypt(Array[Byte](1)))
          r  <- EitherT(action)
        yield r).value
    }
  }

  class GitProvisioner[F[_]: Sync](repoDir: Path) extends Provisioner[Mid[F, *]] {

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] =
      _.flatMap {
        case Right(_) =>
          GitRepo.createNew(repoDir).use { repo =>
            val files = NonEmptyList.of(repoDir.resolve(repoMetadataFile))
            EitherT(repo.commitFiles(files, "Init repository"))
              .bimap(ge => ProvisionErr.Undefined, _ => ())
              .value
          }
        case e => e.pure
      }
  }

  class LoggingProvisioner[F[_]: Monad: Logger] extends Provisioner[Mid[F, *]]:

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] =
      action =>
        log.info("start provisioning") *>
          action.flatTap {
            case Left(e)  => log.error(s"Failed provisioning ${e}")
            case Right(_) => log.info("Provisioning completed")
          }

  class MetaProvisioner[F[_]: Sync](repoDir: Path) extends Provisioner[F]:
    private val fullPath = repoDir.resolve(repoMetadataFile)
    val S                = Sync[F]
    import S.blocking

    override def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]] =
      blocking(Files.exists(repoDir)).flatMap { exists =>
        if exists then ProvisionErr.AlreadyExists.asLeft.pure[F]
        else {
          for
            _ <- blocking(Files.createDirectory(repoDir))
            _ <- blocking(Files.writeString(fullPath, config.asJson.noSpaces, CREATE, WRITE))
          yield ().asRight
        }
      }

enum RepoMetaErr:
  case NotInitialized, InvalidFormat

object RepositoryConfigReader:

  def make[F[_]: Logger](
      repoDir: Path
    )(using
      F: Sync[F]): RepositoryConfigReader[F] = new RepositoryConfigReader[F]:
    private val fullPath = repoDir.resolve(".repo")
    import F.blocking

    def read: F[Either[RepoMetaErr, RepositoryMetaConfig]] =
      blocking(Files.exists(fullPath)).flatMap { exists =>
        if !exists then RepoMetaErr.NotInitialized.asLeft.pure[F]
        else {
          for
            raw <- blocking(Files.readString(fullPath))
            ctx <- blocking(parser.parse(raw).flatMap(_.as[RepositoryMetaConfig]))
          yield ctx.leftMap(_ => RepoMetaErr.InvalidFormat)
        }
      }
