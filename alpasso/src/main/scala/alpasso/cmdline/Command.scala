package alpasso.cmdline

import java.nio.file.Path

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cmdline.view.*
import alpasso.common.syntax.*
import alpasso.common.{ Logger, Result, SemVer }
import alpasso.core.model.*
import alpasso.service.cypher.*
import alpasso.service.fs.*
import alpasso.service.fs.RepositoryErr.fromGitError
import alpasso.service.fs.model.{ Branch, * }
import alpasso.service.git.{GitError, GitRepo}

import glass.*

enum Err:
  case SecretRepoErr(err: RepositoryErr)

  case StorageNotInitialized(path: Path)
  case InconsistentStorage(reason: String)
  case StorageCorrupted(path: Path)

  case InternalErr
  case CommandSyntaxError(help: String)
  case UseSwitchCommand
  case RepositoryProvisionErr(err: ProvisionErr)

object Err:
  given Upcast[Err, RepositoryErr] = Err.SecretRepoErr(_)

  given Upcast[Err, RepoMetaErr] = _ => Err.InternalErr

  given Upcast[Err, ProvisionErr] = e => Err.RepositoryProvisionErr(e)

  given Upcast[Err, CypherError] = e => Err.SecretRepoErr(e.upcast)

end Err

def bootstrap[F[_]: Sync: Logger](repoDir: Path, version: SemVer, cypher: CypherAlg): F[Result[StorageView]] =
  val provisioner = RepositoryProvisioner.make(repoDir)
  val config      = RepositoryMetaConfig(version, cypher)
  provisioner.provision(config).liftE[Err].map(_ => StorageView(repoDir, cypher)).value

def historyLog[F[_] : Sync](configuration: RepositoryConfiguration): F[Result[HistoryLogView]] =
  GitRepo.openExists(configuration.repoDir).use { git =>
    import RepositoryErr.*
    given Upcast[Err, GitError] =
      ge => summon[Upcast[Err, RepositoryErr]].upcast(fromGitError(ge)) // todo fix it

    git.history().nested.map(v => HistoryLogView.from(v.commits)).value.liftE[Err].value
  }

trait Command[F[_]]:

  def create(
      name: SecretName,
      payload: SecretPayload,
      meta: Option[SecretMetadata]): F[Result[SecretView]]

  def patch(
      name: SecretName,
      payload: Option[SecretPayload],
      meta: Option[SecretMetadata]): F[Result[SecretView]]

  def remove(name: SecretName): F[Result[SecretView]]
  def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]]

object Command:

  def make[F[_]: Async: Logger](config: RepositoryConfiguration): Command[F] =
    val cs = config.cypherAlg match
      case CypherAlg.Gpg(fingerprint) => CypherService.gpg(fingerprint)

    val reader  = RepositoryReader.make(config, cs)
    val mutator = RepositoryMutator.make(config)
    Impl[F](cs, reader, mutator)

  private class Impl[F[_]: Async: Logger](
      cs: CypherService[F],
      reader: RepositoryReader[F],
      mutator: RepositoryMutator[F])
      extends Command[F]:

    override def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]] =
      def predicate(s: SecretPacket[(RawSecretData, RawMetadata)]): Boolean =
        filter match
          case SecretFilter.Grep(pattern) => s.name.contains(pattern)
          case SecretFilter.Empty         => true

      def load(s: SecretPacket[RawStoreLocations]) =
        reader.loadFully(s).liftE[Err]

      (for
        rawTree <- reader.walkTree.liftE[Err]
        tree    <- rawTree.traverse(branch => branch.traverse(load))
      yield cutTree(tree, predicate)
        .map(
          _.traverse(b =>
            Id(
              b.map(sm =>
                SecretView(sm.name,
                  new String(sm.payload._1.byteArray).some,
                  sm.payload._2.into().some
                )
              )
            )
          )
        )).value

    override def create(
        name: SecretName,
        payload: SecretPayload,
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val rmd = meta.map(RawMetadata.from).getOrElse(RawMetadata.empty)
      val result =
        for
          data      <- cs.encrypt(payload.rawData).liftE[Err]
          locations <- mutator.create(name, RawSecretData.from(data), rmd).liftE[Err]
        yield SecretView(name, None, meta)

      result.value

    override def remove(name: SecretName): F[Result[SecretView]] =
      val result = for
        catalog <- reader.walkTree.liftE[Err]
        exists <- catalog
          .find(_.fold(false, _.name == name))
          .flatMap(_.toOption)
          .toRight(RepositoryErr.NotFound(name))
          .pure[F]
          .liftE[Err]
        _ <- mutator.remove(exists.name).liftE[Err]
      yield SecretView(name, None, None)

      result.value

    override def patch(
        name: SecretName,
        payload: Option[SecretPayload],
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val result =
        for
          catalog <- reader.walkTree.liftE[Err]
          exists <- catalog
                      .find(_.fold(false, _.name == name))
                      .flatMap(_.toOption)
            .toRight(RepositoryErr.NotFound(name))
            .pure[F]
            .liftE[Err]

          toUpdate <- reader.loadFully(exists).liftE[Err]

          rsd = payload.map(_.rawData).getOrElse(toUpdate.payload._1.byteArray)
          rmd = meta.map(RawMetadata.from).getOrElse(toUpdate.payload._2)

          sec       <- cs.encrypt(rsd).liftE[Err]
          locations <- mutator.update(name, RawSecretData.from(sec), rmd).liftE[Err]
        yield SecretView(name, None, None)

      result.value
end Command
