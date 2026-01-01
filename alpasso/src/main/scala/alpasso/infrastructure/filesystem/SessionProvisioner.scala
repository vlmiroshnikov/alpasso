package alpasso.infrastructure.filesystem

import java.nio.file.StandardOpenOption.{ CREATE_NEW, WRITE }
import java.nio.file.{ Path, StandardOpenOption }

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import cats.tagless.*

import tofu.higherKind.*
import tofu.higherKind.Mid.*

import alpasso.infrastructure.cypher.{ CypherAlg, CypherService }
import alpasso.infrastructure.filesystem.FileEffects.{ createDirectories, pathExists, writeString }
import alpasso.infrastructure.filesystem.PersistentModels.RepositoryMetaConfig
import alpasso.infrastructure.git.GitRepo
import alpasso.shared.models.SemVer

import io.circe.*
import io.circe.derivation.*
import io.circe.syntax.given

object PersistentModels:
  given Configuration = Configuration.default.withSnakeCaseMemberNames

  case class RepositoryMetaConfig(version: SemVer, cryptoAlg: CypherAlg) derives ConfiguredCodec
end PersistentModels

enum ProvisionErr:
  case AlreadyExists(path: Path)
  case Undefined

trait Provisioner[F[_]] derives ApplyK:
  def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]]

object SessionProvisioner:
  import StandardOpenOption.*

  val repoMetadataFile: String = ".alpasso"

  def make[F[_]: {Sync, Console}](repoDir: Path): Provisioner[F] =
    val alg = MetaProvisioner(repoDir)

    val gitted: Provisioner[Mid[F, *]] = GitProvisioner[F](repoDir)
    val logged: Provisioner[Mid[F, *]] = LoggingProvisioner[F](repoDir)
    val cs: Provisioner[Mid[F, *]]     = CypherProvisioner[F]

    (cs |+| gitted |+| logged) attach alg

  class CypherProvisioner[F[_]: {Sync}] extends Provisioner[Mid[F, *]] {

    override def provision(
        config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] = { action =>
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

  class LoggingProvisioner[F[_]: {Monad, Console as Out}](repoDir: Path)
      extends Provisioner[Mid[F, *]]:

    override def provision(config: RepositoryMetaConfig): Mid[F, Either[ProvisionErr, Unit]] =
      action =>
        Out.println("start provisioning") *>
          action.flatTap {
            case Left(e)  => Out.println(s"Failed provisioning ${e}")
            case Right(_) => Out.println(s"Provisioning completed ${repoDir}")
          }

  class MetaProvisioner[F[_]: Sync as F](repoDir: Path) extends Provisioner[F]:
    private val fullPath = repoDir.resolve(repoMetadataFile)

    override def provision(config: RepositoryMetaConfig): F[Either[ProvisionErr, Unit]] =
      pathExists(fullPath).flatMap { exists =>
        if exists then ProvisionErr.AlreadyExists(fullPath).asLeft.pure[F]
        else
          for
            _ <- createDirectories(repoDir)
            _ <- writeString(fullPath, config.asJson.noSpaces, List(CREATE_NEW, WRITE))
          yield ().asRight
      }
