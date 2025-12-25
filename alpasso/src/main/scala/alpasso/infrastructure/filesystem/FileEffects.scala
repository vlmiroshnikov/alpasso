package alpasso.infrastructure.filesystem

import java.nio.file.{ Files, OpenOption, Path }

import cats.*
import cats.data.StateT
import cats.effect.*

import alpasso.infrastructure.filesystem.models.SecretPathEntries
import alpasso.infrastructure.filesystem.RepositoryMutator.StateF

object FileEffects:

  def pathExists[F[_]: Sync](path: Path): StateF[F, Boolean] =
    StateT.liftF(Sync[F].blocking(Files.exists(path)))

  def deleteIfExists[F[_]: Sync](path: Path): StateF[F, Boolean] =
    StateT.liftF(Sync[F].blocking(Files.deleteIfExists(path)))

  def createDirectories[F[_]: Sync](path: Path): StateF[F, Path] =
    StateT.liftF(Sync[F].blocking(Files.createDirectories(path)))

  def write[F[_]: Sync](path: Path, data: Array[Byte], options: List[OpenOption]): StateF[F, Path] =
    StateT.liftF(Sync[F].blocking(Files.write(path, data, options*)))

  def writeString[F[_]: Sync](path: Path, content: String, options: List[OpenOption]): StateF[F, Path] =
    StateT.liftF(Sync[F].blocking(Files.writeString(path, content, options*)))

  def checkSecretExists[F[_]: Sync](p: SecretPathEntries): StateF[F, Boolean] =
    pathExists[F](p.root).flatMap { rootExists =>
      if rootExists then pathExists[F](p.payload).map(_ && rootExists) else StateT.pure(false)
    }
