package alpasso.cmdline

import java.nio.file.Path

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cmdline.view.*
import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.service.fs.*
import alpasso.service.fs.model.*
import alpasso.service.git.*

import glass.*

enum Err:
  case AlreadyExists(name: SecretName)
  case StorageNotInitialized(path: Path)
  case InconsistentStorage(reason: String)
  case StorageCorrupted(path: Path)
  case SecretNotFound(name: SecretName)
  case InternalErr

object Err:
  given Upcast[Err, GitError]   = fromGitError(_)
  given Upcast[Err, StorageErr] = fromStorageErr(_)

  private def fromGitError(ge: GitError): Err =
    ge match
      case GitError.RepositoryNotFound(path) => Err.StorageNotInitialized(path)
      case GitError.RepositoryIsDirty        => Err.InconsistentStorage("repo is dirty")
      case GitError.UnexpectedError          => Err.InternalErr

  private def fromStorageErr(se: StorageErr): Err =
    se match
      case StorageErr.NotInitialized           => Err.InternalErr
      case StorageErr.FileNotFound(path, name) => Err.InconsistentStorage(s"file not found ${path}")
      case StorageErr.DirAlreadyExists(_, name)      => Err.AlreadyExists(name)
      case StorageErr.MetadataFileCorrupted(path, _) => Err.StorageCorrupted(path)
end Err

trait Command[F[_]]:
  def initWithPath(repoDir: Path): F[RejectionOr[StorageView]]
  def create(name: SecretName, payload: SecretPayload, meta: Metadata): F[RejectionOr[SecretView]]
  def update(name: SecretName, payload: Option[SecretPayload], meta: Option[Metadata]): F[RejectionOr[SecretView]]
  def filter(filter: SecretFilter): F[RejectionOr[Option[Node[Branch[SecretView]]]]]

object Command:
  def make[F[_]: Async](ls: LocalStorage[F]): Command[F] = Impl[F](ls)

  private class Impl[F[_]: Async](ls: LocalStorage[F]) extends Command[F]:

    override def initWithPath(repoDir: Path): F[RejectionOr[StorageView]] =
      GitRepo.createNew(repoDir).use(r => r.info.map(StorageView(_).asRight))

    override def filter(filter: SecretFilter): F[RejectionOr[Option[Node[Branch[SecretView]]]]] =
      def predicate(s: Secret[Metadata]): Boolean =
        filter match
          case SecretFilter.Predicate(pattern) =>
            s.name.contains(pattern)
          case SecretFilter.Empty => false
          case SecretFilter.All   => true

      val buildTree = for
        home    <- ls.repoDir().liftTo[Err]
        _       <- GitRepo.openExists(home).use(_.verify).liftTo[Err]
        rawTree <- ls.walkTree.liftTo[Err]
        tree    <- rawTree.traverse:
                  case Branch.Empty(dir) => EitherT.pure(Branch.Empty(dir))
                  case Branch.Solid(dir, s) =>
                    ls.loadMeta(s.map(_.metadata)).liftTo[Err].map(m => Branch.Solid(dir, m))
      yield cutTree[Secret[Metadata]](tree, predicate)
        .map(_.traverse(b => Id(b.map(sm => SecretView(sm.name, MetadataView(sm.payload))))))

      buildTree.value

    override def create(name: SecretName, payload: SecretPayload, meta: Metadata): F[RejectionOr[SecretView]] =
      def addNewSecret(git: GitRepo[F], secret: Secret[RawSecretData]) = // todo use saga
        val commitMsg = s"Create secret ${name} at ${sys.env.getOrElse("HOST_NAME", "")}"
        for
          file <- ls.create(secret.name, secret.payload, meta).liftTo[Err]
          _ <- git
                 .commitFiles(NonEmptyList.of(file.payload.secretData, file.payload.metadata),
                              commitMsg
                 )
                 .liftTo[Err]
        yield SecretView(file.name, MetadataView(meta))

      val result =
        for
          home <- ls.repoDir().liftTo[Err]
          secret = Secret(name, RawSecretData.from(payload.rawData))
          r <- GitRepo.openExists(home).use(addNewSecret(_, secret).value).liftTo[Err]
        yield r

      result.value

    override def update(
        name: SecretName,
        payload: Option[SecretPayload],
        meta: Option[Metadata]): F[RejectionOr[SecretView]] =
      def updateExists(
          git: GitRepo[F],
          secret: Secret[(RawSecretData, Metadata)]) = // todo use saga
        val commitMsg = s"Update secret ${name} at ${sys.env.getOrElse("HOST_NAME", "")}"
        for
          file <- ls.update(secret.name, secret.payload._1, secret.payload._2).liftTo[Err]
          _ <- git
                 .commitFiles(NonEmptyList.of(file.payload.secretData, file.payload.metadata),
                              commitMsg
                 )
                 .liftTo[Err]
          updated <- ls.loadMeta(file.map(_.metadata)).liftTo[Err]
        yield SecretView(updated.name, MetadataView(updated.payload))

      val result =
        for
          home    <- ls.repoDir().liftTo[Err]
          catalog <- ls.walkTree.liftTo[Err]
          exists <- catalog
                      .find(_.fold(false, _.name == name))
                      .flatMap(_.toOption)
                      .toRight(Err.SecretNotFound(name))
                      .toEitherT

          toUpdate <-
            (ls.loadPayload(exists.map(_.secretData)).liftTo[Err],
             ls.loadMeta(exists.map(_.metadata)).liftTo[Err]
            )
              .mapN((rawSecret, rawMeta) =>
                (payload.map(p => RawSecretData.from(p.rawData)).getOrElse(rawSecret.payload),
                 meta.getOrElse(rawMeta.payload)
                )
              )
          r <-
            GitRepo.openExists(home).use(updateExists(_, Secret(name, toUpdate)).value).liftTo[Err]
        yield r

      result.value
end Command
