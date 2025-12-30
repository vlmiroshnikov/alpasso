package alpasso.infrastructure.filesystem

import java.nio.file.*
import java.nio.file.StandardOpenOption.*

import cats.*
import cats.data.{ NonEmptyList as NEL, * }
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import cats.tagless.*

import alpasso.domain.*
import alpasso.infrastructure.cypher.CypherService
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.GitRepo
import alpasso.shared.syntax.*

import tofu.higherKind.*
import tofu.higherKind.Mid.*

case class RawData(data: RawSecretData, meta: RawMetadata)

trait RepositoryMutator[F[_]] derives ApplyK:

  def create(
      name: SecretName,
      meta: RawMetadata): F[Result[Unit]]

  def update(
      name: SecretName,
      meta: RawMetadata): F[Result[Unit]]

  def remove(name: SecretName): F[Result[Unit]]

object RepositoryMutator:

  type State           = Option[RawSecretData]
  type StateF[F[_], A] = StateT[F, State, A]

  def make[F[_]: {Sync, Console}](
      config: RepositoryConfiguration,
      cs: CypherService[F]): RepositoryMutator[StateF[F, *]] =
    val gitted: RepositoryMutator[Mid[StateF[F, *], *]]  = Gitted[F](config.repoDir)
    val crypted: RepositoryMutator[Mid[StateF[F, *], *]] = Crypted[F](cs)
    val logged: RepositoryMutator[Mid[StateF[F, *], *]]  = Logging[F]()
    (logged |+| crypted |+| gitted).attach(Impl[F](config.repoDir))

  class Impl[F[_]: {Sync as F}](repoDir: RepoRootDir) extends RepositoryMutator[StateF[F, *]] {
    import FileEffects.*

    private val CreateOps: List[OpenOption] = List(CREATE_NEW, WRITE)
    private val UpdateOps: List[OpenOption] = List(CREATE, TRUNCATE_EXISTING, WRITE)

    override def remove(name: SecretName): StateF[F, Result[Unit]] =
      val p = SecretPathEntries.from(repoDir, name)

      pathExists(p.payload).flatMap { rootExists =>
        if !rootExists then StateT.pure(RepositoryErr.NotFound(name).asLeft)
        else
          for
            _ <- deleteIfExists(p.meta)
            _ <- deleteIfExists(p.payload)
            _ <- deleteIfExists(p.root)
          yield ().asRight[RepositoryErr]
      }

    override def create(
        name: SecretName,
        meta: RawMetadata): StateF[F, Result[Unit]] =
      val p = SecretPathEntries.from(repoDir, name)

      checkSecretExists(p).flatMap { rootExists =>
        if rootExists then StateT.pure(RepositoryErr.AlreadyExists(name).asLeft)
        else
          for
            _          <- createDirectories(p.root)
            encodedOpt <- StateT.get[F, State]
            _ <- write(p.payload, encodedOpt.getOrElse(RawSecretData.empty).byteArray, CreateOps)
            _ <- writeString(p.meta, meta.rawString, CreateOps)
          yield ().asRight
      }

    override def update(
        name: SecretName,
        metadata: RawMetadata): StateF[F, Result[Unit]] =

      val p = SecretPathEntries.from(repoDir, name)

      checkSecretExists(p).flatMap { rootExists =>
        if rootExists then StateT.pure(RepositoryErr.Corrupted(name).asLeft)
        else
          for
            encodedOpt <- StateT.get[F, State]
            _ <- write(p.payload, encodedOpt.getOrElse(RawSecretData.empty).byteArray, UpdateOps)
            _ <- writeString(p.meta, metadata.rawString, UpdateOps)
          yield ().asRight
      }
  }

  class Crypted[F[_]: Sync](cs: CypherService[F]) extends RepositoryMutator[Mid[StateF[F, *], *]] {

    override def create(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          st  <- StateT.get[F, State]
          enc <- StateT.liftF(cs.encrypt(st.get.byteArray).liftE[RepositoryErr].value)
          _   <- StateT.set[F, State](enc.toOption.map(RawSecretData.fromRaw))
          res <- action
        yield res
      }

    override def update(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          st  <- StateT.get[F, State]
          enc <- StateT.liftF(cs.encrypt(st.get.byteArray).liftE[RepositoryErr].value)
          _   <- StateT.set[F, State](enc.toOption.map(RawSecretData.fromRaw))
          res <- action
        yield res
      }

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] = identity

  }

  class Logging[F[_]: {Sync, Console as Con}] extends RepositoryMutator[Mid[StateF[F, *], *]] {

    override def create(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Con.println(s"Creating secret: $name"))
          res <- action
          _   <- StateT.liftF(
                 res.fold(_ => Con.println(s"Secret created: $name"),
                          err => Con.println(s"Failed to create secret [$name]: $err")
                 )
               )
        yield res
      }

    override def update(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Con.println(s"Updating secret: $name"))
          res <- action
          _   <- StateT.liftF(
                 res.fold(_ => Con.println(s"Secret updated: $name"),
                          err => Con.println(s"Failed to update secret [$name]: $err")
                 )
               )
        yield res
      }

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Con.println(s"Removing secret: $name"))
          res <- action
          _   <- StateT.liftF(
                 res.fold(_ => Con.println(s"Secret removed: $name"),
                          err => Con.println(s"Failed to remove secret [$name]: $err")
                 )
               )
        yield res
      }
  }

  class Gitted[F[_]: Sync](repoDir: RepoRootDir) extends RepositoryMutator[Mid[StateF[F, *], *]] {

    override def create(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {

        val commitMsg = s"Add secret [$name]"
        val locs      = SecretPathEntries.from(repoDir, name)

        val fileNames = NEL.of(locs.payload, locs.meta)

        val r = for {
          st <- action.toEitherT
          _  <- StateT
                 .liftF(GitRepo.openExists(repoDir).use(_.commitFiles(fileNames, commitMsg)))
                 .liftE[RepositoryErr]
        } yield ()

        r.value
      }

    override def update(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {

        val commitMsg = s"Update secret [$name]"
        val locs      = SecretPathEntries.from(repoDir, name)

        val fileNames = NEL.of(locs.payload, locs.meta)

        val r = for {
          st <- action.toEitherT
          _  <- StateT
                 .liftF(GitRepo.openExists(repoDir).use(_.commitFiles(fileNames, commitMsg)))
                 .liftE[RepositoryErr]
        } yield ()

        r.value
      }

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] =
      action => {

        val commitMsg = s"Remove secret [$name]"
        val locs      = SecretPathEntries.from(repoDir, name)

        val fileNames = NEL.of(locs.payload, locs.meta)

        val r = for {
          st <- action.toEitherT
          _  <- StateT
                 .liftF(GitRepo.openExists(repoDir).use(_.removeFiles(fileNames, commitMsg)))
                 .liftE[RepositoryErr]
        } yield ()

        r.value
      }

  }
