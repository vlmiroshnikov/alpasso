package alpasso.service.fs

import java.nio.file.{ Files, Path, StandardOpenOption }

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*

import alpasso.common.SemVer
import alpasso.service.cypher.{ CypherAlg, CypherService }
import alpasso.service.git.GitRepo

import io.circe.*
import io.circe.derivation.*
import io.circe.syntax.given
import tofu.higherKind.*
import tofu.higherKind.Mid.*

object PersistentModels:
  given Configuration = Configuration.default.withSnakeCaseMemberNames

  case class RepositoryMetaConfig(version: SemVer, cryptoAlg: CypherAlg) derives ConfiguredCodec
end PersistentModels

import PersistentModels.*

trait RepositoryConfigReader[F[_]]:
  def read(path: Path): F[Either[RepoMetaErr, RepositoryMetaConfig]]

enum ProvisionErr:
  case AlreadyExists(path: Path)
  case Undefined

trait Provisioner[F[_]] derives ApplyK:
  def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]]

object RepositoryProvisioner:
  import StandardOpenOption.*

  val repoMetadataFile: String = ".alpasso"

  def make[F[_]: { Sync }](repoDir: Path): Provisioner[F] =
    val alg = MetaProvisioner(repoDir)

    val gitted: Provisioner[Mid[F, *]] = GitProvisioner[F](repoDir)
    val logged: Provisioner[Mid[F, *]] = LoggingProvisioner[F]
    val cs: Provisioner[Mid[F, *]]     = CypherProvisioner[F]

    (cs |+| gitted |+| logged) attach alg

  class CypherProvisioner[F[_]: { Sync }] extends Provisioner[Mid[F, *]] {

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] = {
      action =>
        val cs = config.cryptoAlg match
          case CypherAlg.Gpg(fingerprint) => CypherService.gpg(fingerprint)

        (for
          _ <- EitherT.liftF(cs.encrypt(Array[Byte](1)))
          r <- EitherT(action)
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

  class LoggingProvisioner[F[_]: Sync as F] extends Provisioner[Mid[F, *]]:
    import F.blocking

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] =
      action =>
        blocking(println("start provisioning")) *>
          action.flatTap {
            case Left(e)  => blocking(println(s"Failed provisioning ${e}"))
            case Right(_) => blocking(println("Provisioning completed"))
          }

  class MetaProvisioner[F[_]: Sync as F](repoDir: Path) extends Provisioner[F]:

    import F.blocking

    private val fullPath = repoDir.resolve(repoMetadataFile)

    override def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]] =
      blocking(Files.exists(fullPath)).flatMap { exists =>
        if exists then ProvisionErr.AlreadyExists(fullPath).asLeft.pure[F]
        else {
          for
            _ <- blocking(Files.createDirectory(repoDir))
            _ <- blocking(Files.writeString(fullPath, config.asJson.noSpaces, CREATE_NEW, WRITE))
          yield ().asRight
        }
      }

enum RepoMetaErr:
  case NotInitialized(path: Path)
  case InvalidFormat(path: Path)

object RepositoryConfigReader:

  def make[F[_]: { Sync as S }]: RepositoryConfigReader[F] = (repoDir: Path) =>
    import S.blocking

    val fullPath = repoDir.resolve(RepositoryProvisioner.repoMetadataFile)

    blocking(Files.exists(fullPath)).flatMap { exists =>
      if !exists then RepoMetaErr.NotInitialized(fullPath).asLeft.pure[F]
      else
        for
          raw <- blocking(Files.readString(fullPath))
          ctx <- blocking(parser.parse(raw).flatMap(_.as[RepositoryMetaConfig]))
        yield ctx.leftMap(_ => RepoMetaErr.InvalidFormat(fullPath))
    }
