package alpasso.cmdline

import java.nio.file.Path

import scala.annotation.experimental

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cmdline.view.*
import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.service.cypher.*
import alpasso.service.fs.*
import alpasso.service.fs.model.*
import alpasso.service.fs.repo.model.{ CryptoAlg, RepositoryConfiguration, RepositoryMetaConfig }
import alpasso.service.fs.repo.{ ProvisionErr, RepoMetaErr, RepositoryProvisioner }
import alpasso.service.git.*
import alpasso.shared.SemVer

import glass.*

enum Err:
  case AlreadyExists(name: SecretName)
  case StorageNotInitialized(path: Path)
  case InconsistentStorage(reason: String)
  case StorageCorrupted(path: Path)
  case SecretNotFound(name: SecretName)
  case CypherErr
  case InternalErr

object Err:
  given Upcast[Err, GitError] = fromGitError(_)
  // given Upcast[Err, StorageErr]   = fromStorageErr(_)
  given Upcast[Err, Unit]         = _ => Err.CypherErr
  given Upcast[Err, RepoMetaErr]  = _ => Err.InternalErr
  given Upcast[Err, ProvisionErr] = _ => Err.InternalErr

  private def fromGitError(ge: GitError): Err =
    ge match
      case GitError.RepositoryNotFound(path) => Err.StorageNotInitialized(path)
      case GitError.RepositoryIsDirty        => Err.InconsistentStorage("repo is dirty")
      case GitError.UnexpectedError          => Err.InternalErr
end Err

trait Command[F[_]]:
  def initWithPath(repoDir: Path, version: SemVer, alg: CryptoAlg): F[RejectionOr[StorageView]]

  def create(
      name: SecretName,
      payload: SecretPayload,
      meta: Metadata,
      config: RepositoryConfiguration): F[RejectionOr[SecretView]]

  def update(
      name: SecretName,
      payload: Option[SecretPayload],
      meta: Option[Metadata],
      config: RepositoryConfiguration): F[RejectionOr[SecretView]]
  def filter(filter: SecretFilter, config: RepositoryConfiguration): F[RejectionOr[Option[Node[Branch[SecretView]]]]]

@experimental
object Command:
  def make[F[_]: Async: Logger]: Command[F] = Impl[F]

  private class Impl[F[_]: Async: Logger] extends Command[F]:

    override def initWithPath(repoDir: Path, version: SemVer, alg: CryptoAlg): F[RejectionOr[StorageView]] =
      val provisioner = RepositoryProvisioner.make(repoDir)
      val config      = RepositoryMetaConfig(SemVer.zero, alg)
      provisioner.provision(config).liftE[Err].map(_ => StorageView(repoDir)).value

    override def filter(filter: SecretFilter, config: RepositoryConfiguration): F[RejectionOr[Option[Node[Branch[SecretView]]]]] =
      def predicate(s: SecretPacket[(RawSecretData, Metadata)]): Boolean =
        filter match
          case SecretFilter.Predicate(pattern) =>
            s.name.contains(pattern)
          case SecretFilter.Empty => false
          case SecretFilter.All   => true

      CypherService
        .make(config.cryptoAlg)
        .liftE[Err]
        .flatMap { cs =>
          val ls = LocalStorage.make(config, cs)

          for
            rawTree <- ls.walkTree.liftE[Err]
            tree <- rawTree.traverse:
                      case Branch.Empty(dir) => EitherT.pure(Branch.Empty(dir))
                      case Branch.Solid(dir, s) =>
                        ls.loadFully(s).liftE[Err].map(m => Branch.Solid(dir, m))
          yield cutTree(tree, predicate)
            .map(
              _.traverse(b =>
                Id(
                  b.map(sm =>
                    SecretView(sm.name,
                               MetadataView(sm.payload._2),
                               new String(sm.payload._1.byteArray).some
                    )
                  )
                )
              )
            )
        }
        .value

    override def create(
        name: SecretName,
        payload: SecretPayload,
        meta: Metadata,
        config: RepositoryConfiguration): F[RejectionOr[SecretView]] =
      val result =
        for
          cs   <- CypherService.make(config.cryptoAlg).liftE[Err]
          data <- cs.encrypt(payload.rawData).liftE[Err]
          secret = SecretPacket(name, RawSecretData.from(data))
          locations <-
            LocalStorage.make(config, cs).create(secret.name, secret.payload, meta).liftE[Err]
        yield SecretView(locations.name, MetadataView(meta))

      result.value

    override def update(
        name: SecretName,
        payload: Option[SecretPayload],
        meta: Option[Metadata],
        config: RepositoryConfiguration): F[RejectionOr[SecretView]] =
      val result =
        for
          cs <- CypherService.make(config.cryptoAlg).liftE[Err]
          ls = LocalStorage.make(config, cs)
          catalog <- ls.walkTree.liftE[Err]
          exists <- catalog
                      .find(_.fold(false, _.name == name))
                      .flatMap(_.toOption)
                      .toRight(Err.SecretNotFound(name))
                      .toEitherT

          toUpdate <- ls.loadFully(exists).liftE[Err]

          rsd = payload.map(_.rawData).getOrElse(toUpdate.payload._1.byteArray)
          sec <- cs.encrypt(rsd).liftE[Err]
          metadata = meta.getOrElse(toUpdate.payload._2)
          locations <- ls.update(name, RawSecretData.from(sec), metadata).liftE[Err]
        yield SecretView(locations.name, MetadataView(metadata))

      result.value
end Command
