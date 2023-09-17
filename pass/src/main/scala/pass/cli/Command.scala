package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*

import java.nio.ByteBuffer
import java.nio.file.{ Files, Path, StandardOpenOption }
import glass.*

import java.nio.charset.Charset

enum Err:
  case AlreadyExists(name: Name)
  case StorageNotInitialized(path: Path)
  case InternalErr

object Err:
  given Upcast[Err, GitError]   = fromGitError
  given Upcast[Err, StorageErr] = fromStorageErr

  @FunctionalInterface
  def fromGitError(ge: GitError): Err =
    ge match
      case GitError.RepositoryNotFound(path) => Err.StorageNotInitialized(path)
      case GitError.UnexpectedError          => Err.InternalErr

  @FunctionalInterface
  def fromStorageErr(se: StorageErr): Err =
    se match
      case StorageErr.NotInitialized            => Err.InternalErr
      case StorageErr.DirAlreadyExists(_, name) => Err.AlreadyExists(name)

enum StorageErr:
  case NotInitialized
  case DirAlreadyExists(path: Path, name: Name)

case class Tag(name: String, value: String)

enum Metadata:
  case HostName(name: String)
  case UserName(name: String)
  case UserData(tags: List[Tag])

case class RawStoreEntry(payload: Path, meta: Path)

trait StorageCtx:
  def repoDir: Path
  def resolve(file: String): Path

opaque type Payload = Array[Byte]

object Payload:
  def from(bytes: Array[Byte]): Payload             = bytes
  extension (p: Payload) def byteArray: Array[Byte] = p

trait LocalStorage[F[_]]:
  def repoDir(): F[Either[StorageErr, Path]]
  def createFiles(name: Name, payload: Payload): F[Either[StorageErr, RawStoreEntry]]

object LocalStorage:
  import fs2.io.file.Files as F2Files
  import fs2.io.file.Path as F2Path

  def make[F[_]: Sync](home: String): LocalStorage[F] =
    val ctx = new StorageCtx:
      override def repoDir: Path = java.nio.file.Path.of(home)
      override def resolve(file: String): Path =
        java.nio.file.Path.of(home, file)
    Impl[F](ctx)

  class Impl[F[_]](
      using
      F: Sync[F]
    )(ctx: StorageCtx)
      extends LocalStorage[F]:
    import F.blocking

    override def repoDir(): F[Either[StorageErr, Path]] = ctx.repoDir.asRight.pure

    override def createFiles(name: Name, payload: Payload): F[Either[StorageErr, RawStoreEntry]] =
      val path = ctx.resolve(name)

      blocking(Files.exists(path)).flatMap { exists =>
        if exists then StorageErr.DirAlreadyExists(path, name).asLeft.pure[F]
        else
          val metaPath    = path.resolve("meta")
          val payloadPath = path.resolve("payload")
          for
            _ <- blocking(Files.createDirectories(path))
            _ <- blocking(
                   Files.write(payloadPath,
                               payload.byteArray,
                               StandardOpenOption.CREATE_NEW,
                               StandardOpenOption.WRITE))
            _ <- blocking(
                   Files.writeString(metaPath,
                                     "",
                                     StandardOpenOption.CREATE_NEW,
                                     StandardOpenOption.WRITE))
          yield RawStoreEntry(payloadPath, metaPath).asRight
      }

case class SecretView(name: Name)
case class ErrorView(code: String, explain: Option[String])

case class StorageView(repoDir: Path)

opaque type Secret = Array[Byte]

object Secret:
  private val utf8Charset                = Charset.forName("UTF-8")
  def fromRaw(data: Array[Byte]): Secret = data
  def fromString(data: String): Secret   = data.getBytes(utf8Charset)

  extension (s: Secret) def rawData: Array[Byte] = s

type RejectionOr[A] = Either[Err, A]

trait CryptoApi[F[_]]:
  def encript(data: ByteBuffer): F[ByteBuffer]

extension [F[_]: Functor, A, B](fa: F[Either[A, B]])
  def toEitherT: EitherT[F, A, B] = EitherT(fa)

  def liftTo[E](
      using
      up: Upcast[E, A]): EitherT[F, E, B] =
    EitherT(fa).leftMap(a => up.upcast(a))

trait Command[F[_]]:
  def initWithPath(repoDir: Path): F[RejectionOr[StorageView]]
  def create(name: Name, secret: Secret): F[RejectionOr[SecretView]]

object Command:
  def make[F[_]: Async](ls: LocalStorage[F]): Command[F] = Impl[F](ls)

  class Impl[F[_]: Async](ls: LocalStorage[F]) extends Command[F]:

    override def initWithPath(repoDir: Path): F[RejectionOr[StorageView]] =
      GitRepo.create(repoDir).use(r => r.info.map(StorageView(_).asRight))

    override def create(name: Name, secret: Secret): F[RejectionOr[SecretView]] =
      def addNewSecret(git: GitRepo[F]) = // todo use saga
        for
          file <- ls.createFiles(name, Payload.from(secret.rawData)).liftTo[Err]
          _ <- git
                 .addFiles(NonEmptyList.of(file.payload, file.meta),
                           s"Create secret $name at ${sys.env.getOrElse("HOST_NAME", "")}")
                 .liftTo[Err]
        yield SecretView(Name.of(file.payload.getFileName.toString))

      val result = for
        home <- ls.repoDir().liftTo[Err]
        r <- GitRepo
               .openExists(home)
               .use:
                 case Right(git) => addNewSecret(git).value
                 case Left(ge)   => Err.fromGitError(ge).asLeft.pure
               .liftTo[Err]
      yield r

      result.value

end Command
