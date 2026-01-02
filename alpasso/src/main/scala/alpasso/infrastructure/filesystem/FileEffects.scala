package alpasso.infrastructure.filesystem

import java.nio.file.{ Files, OpenOption, Path }

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.infrastructure.filesystem.models.SecretPathEntries

object FileEffects:

  def pathExists[F[_]: Sync](path: Path): F[Boolean] =
    Sync[F].blocking(Files.exists(path))

  def deleteIfExists[F[_]: Sync](path: Path): F[Boolean] =
    Sync[F].blocking(Files.deleteIfExists(path))

  def createDirectories[F[_]: Sync](path: Path): F[Path] =
    Sync[F].blocking(Files.createDirectories(path))

  def write[F[_]: Sync](path: Path, data: Array[Byte], options: List[OpenOption]): F[Path] =
    Sync[F].blocking(Files.write(path, data, options*))

  def readString[F[_]: Sync](path: Path): F[String] =
    Sync[F].blocking(Files.readString(path))

  def writeString[F[_]: Sync](
      path: Path,
      content: String,
      options: List[OpenOption]): F[Path] =
    Sync[F].blocking(Files.writeString(path, content, options*))

  def checkSecretExists[F[_]: Sync](p: SecretPathEntries): F[Boolean] =
    (pathExists[F](p.root), pathExists[F](p.payload)).mapN(_ && _)
