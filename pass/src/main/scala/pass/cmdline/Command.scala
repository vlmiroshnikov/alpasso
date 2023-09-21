package pass.cmdline

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import glass.*
import io.circe.*
import io.circe.syntax.*
import pass.cli.*
import pass.core.model.*
import pass.service.fs.{ Branch, LocalStorage }
import pass.service.fs.model.*

import pass.service.git.*

import pass.cmdline.model.*
import pass.common.syntax.*

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.{ Files, Path, StandardOpenOption }

enum Err:
  case AlreadyExists(name: SecretName)
  case StorageNotInitialized(path: Path)
  case InconsistentStorage
  case InternalErr

object Err:
  given Upcast[Err, GitError]   = fromGitError(_)
  given Upcast[Err, StorageErr] = fromStorageErr(_)

  private def fromGitError(ge: GitError): Err =
    ge match
      case GitError.RepositoryNotFound(path) => Err.StorageNotInitialized(path)
      case GitError.RepositoryIsDirty        => Err.InconsistentStorage
      case GitError.UnexpectedError          => Err.InternalErr

  private def fromStorageErr(se: StorageErr): Err =
    se match
      case StorageErr.NotInitialized            => Err.InternalErr
      case StorageErr.FileNotFound(path, name)  => Err.InconsistentStorage
      case StorageErr.DirAlreadyExists(_, name) => Err.AlreadyExists(name)

case class ErrorView(code: String, explain: Option[String])

trait Command[F[_]]:
  def initWithPath(repoDir: Path): F[RejectionOr[StorageView]]
  def create(secret: Secret[SecretPayload]): F[RejectionOr[SecretView]]
  def filter(filter: SecretFilter): F[RejectionOr[Node[Branch[SecretView]]]]

object Command:
  def make[F[_]: Async](ls: LocalStorage[F]): Command[F] = Impl[F](ls)

  private class Impl[F[_]: Async](ls: LocalStorage[F]) extends Command[F]:

    override def initWithPath(repoDir: Path): F[RejectionOr[StorageView]] =
      GitRepo.create(repoDir).use(r => r.info.map(StorageView(_).asRight))

    override def filter(filter: SecretFilter): F[RejectionOr[Node[Branch[SecretView]]]] =
      val buildTree = for
        tree <- ls.walkTree().liftTo[Err]
        treeView <- tree.traverse:
                      case Branch.Empty(dir) => EitherT.pure(Branch.Empty(dir))
                      case Branch.Solid(dir, s) =>
                        ls.loadMeta(s)
                          .liftTo[Err]
                          .map(m => Branch.Solid(dir, SecretView(s.name, MetadataView())))
      yield treeView

      buildTree.value

    override def create(secret: Secret[SecretPayload]): F[RejectionOr[SecretView]] =

      def addNewSecret(git: GitRepo[F]) = // todo use saga
        val commitMsg = s"Create secret ${secret.name} at ${sys.env.getOrElse("HOST", "")}"
        for
          _    <- git.verify().liftTo[Err]
          file <- ls.createFiles(secret.map(ps => Payload.from(ps.rawData))).liftTo[Err]
          _ <- git
                 .addFiles(NonEmptyList.of(file.payload.secret, file.payload.meta), commitMsg)
                 .liftTo[Err]
        yield SecretView(file.name, MetadataView())

      val result =
        for
          home <- ls.repoDir().liftTo[Err]
          r    <- GitRepo.openExists(home).use(addNewSecret(_).value).liftTo[Err]
        yield r

      result.value

end Command
