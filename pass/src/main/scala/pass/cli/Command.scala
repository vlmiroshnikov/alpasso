package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*
import fs2.io.file.{Files, Path}

enum Err :
  case AlreadyExists(name: Name)

type RejectionOr[A] = Either[Err, A]

enum StorageErr:
  case NotInitialized
  case AlreadyExists(path: Path)

case class Tag(name: String, value: String)

enum Metadata:
  case HostName(name: String)
  case UserName(name: String)
  case UserData(tags: List[Tag])

case class RawFile(name: Name, meta: List[Metadata])

trait StorageCtx:
  def resolve(file: String): Path


trait LocalStorage[F[_]]:
  def createFile(name: Name): F[Either[StorageErr, RawFile]]

object LocalStorage:
  def make[F[_] : Monad : Files](home: String): LocalStorage[F]  =
    val ctx = new StorageCtx:
      override def resolve(file: String): Path =
        Path.fromNioPath(java.nio.file.Path.of(home, file))
    Impl[F](ctx)

  def apply[F[_]](using ls : LocalStorage[F]): LocalStorage[F] = ls
  class Impl[F[_] : Monad : Files](ctx: StorageCtx) extends LocalStorage[F]:
    override def createFile(name: Name): F[Either[StorageErr, RawFile]] =
      val path = ctx.resolve(name)
      Files[F].exists(path).flatMap { exists =>
        if exists then StorageErr.AlreadyExists(path).asLeft.pure[F]
        else
          for
            _    <- Files[F].createDirectories(path)
            payload <- Files[F].createFile(path / "payload")
            meta <- Files[F].createFile(path / "meta")
          yield RawFile(name, Nil).asRight
      }

case class FileView(name: Name)
case class ErrorView(code: String, explain: Option[String])



trait Command[F[_]]:
  def create(name: Name): F[RejectionOr[FileView]]

object Command:
  def make[F[_]: Async : LocalStorage]() : Command[F] = Impl[F]
  class Impl[F[_] : Async](using LS: LocalStorage[F]) extends Command[F]:
    def create(name: Name): F[RejectionOr[FileView]] =
      (for
        file <- EitherT(LS.createFile(name))
      yield FileView(file.name))
        .leftMap(se => Err.AlreadyExists(name))
        .value

end Command




