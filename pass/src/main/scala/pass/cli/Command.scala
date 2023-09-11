package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.{ Path, Paths }

enum Err:
  case AlreadyExists(name: Name)

enum StorageErr:
  case NotInitialized
  case AlreadyExists(path: Path)

case class Tag(name: String, value: String)

enum Metadata:
  case HostName(name: String)
  case UserName(name: String)
  case UserData(tags: List[Tag])

case class RawFile(payload: Path, meta: Path)

trait StorageCtx:
  def repoDir: Path
  def resolve(file: String): Path

trait LocalStorage[F[_]]:
  def repoDir(): F[Path]
  def createFile(name: Name): F[Either[StorageErr, RawFile]]

object LocalStorage:
  import fs2.io.file.Files as F2Files
  import fs2.io.file.Path as F2Path

  def make[F[_]: Monad: F2Files](home: String): LocalStorage[F] =
    val ctx = new StorageCtx:
      override def repoDir: Path = java.nio.file.Path.of(home)
      override def resolve(file: String): Path =
        java.nio.file.Path.of(home, file)

    Impl[F](ctx)

  def apply[F[_]](using ls: LocalStorage[F]): LocalStorage[F] = ls

  class Impl[F[_]: Monad: F2Files](ctx: StorageCtx) extends LocalStorage[F]:
    private val FIO: F2Files[F] = F2Files[F]

    override def repoDir(): F[Path] = ctx.repoDir.pure

    override def createFile(name: Name): F[Either[StorageErr, RawFile]] =
      val path = F2Path.fromNioPath(ctx.resolve(name))
      FIO.exists(path).flatMap { exists =>
        if exists then StorageErr.AlreadyExists(path.toNioPath).asLeft.pure[F]
        else
          val meta    = path / "meta"
          val payload = path / "payload"
          for
            _ <- FIO.createDirectories(path)
            _ <- FIO.createFile(payload)
            _ <- FIO.createFile(meta)
          yield RawFile(payload.toNioPath, meta.toNioPath).asRight
      }

case class FileView(name: Name)
case class ErrorView(code: String, explain: Option[String])

case class StorageView(repoDir: Path)

type RejectionOr[A] = Either[Err, A]

trait CryptoApi[F[_]]:
  def encript(data: ByteBuffer): F[ByteBuffer]

type Persisted = [F[_], A] =>> LocalStorage[F] ?=> A
type Encripted = [F[_], A] =>> CryptoApi[F] ?=> A

trait Command[F[_]]:
  def initWithPath(repoDir: Path): F[RejectionOr[StorageView]]
  def create(name: Name): F[RejectionOr[FileView]]

object Command:
  def make[F[_]: Async: LocalStorage]: Command[F] = Impl[F]

  class Impl[F[_]: Async: LocalStorage] extends Command[F]:

    override def initWithPath(repoDir: Path): F[RejectionOr[StorageView]] =
      GitRepo.create(repoDir).use(r => r.info.map(StorageView(_).asRight))

    override def create(name: Name): F[RejectionOr[FileView]] =
      for
        home <- LocalStorage[F].repoDir()
        r <- GitRepo.openExists(home).use { git =>
               (for
                 file <- EitherT(LocalStorage[F].createFile(name))
                 _ <- EitherT.right(
                        git.addFiles(
                          List(file.payload.toFile, file.meta.toFile),
                          s"Create secret ${name} at ${sys.env.getOrElse("HOST_NAME", "")}"))
               yield FileView(Name.of(file.payload.getFileName.toString)))
                 .leftMap(se => Err.AlreadyExists(name))
                 .value
             }
      yield r

end Command
