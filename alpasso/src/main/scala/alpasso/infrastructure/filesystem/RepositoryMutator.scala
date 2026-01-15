package alpasso.infrastructure.filesystem

import java.nio.file.*
import java.nio.file.StandardOpenOption.*

import cats.*
import cats.data.{ NonEmptyList as NEL, * }
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import cats.tagless.*

import tofu.higherKind.*
import tofu.higherKind.Mid.*

import alpasso.domain.*
import alpasso.infrastructure.cypher.CypherService
import alpasso.infrastructure.filesystem.RepositoryMutator.State.Plain
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.{ GitError, GitRepo }
import alpasso.shared.syntax.*

import org.eclipse.jgit.revwalk.RevCommit

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

  enum State:
    case Plain(data: RawSecretData)
    case Encrypted(data: RawSecretData)
    case Empty

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

      StateT.liftF(pathExists(p.payload)).flatMap { rootExists =>
        if !rootExists then StateT.pure(RepositoryErr.NotFound(name).asLeft)
        else
          val remove = for
            _ <- deleteIfExists(p.meta)
            _ <- deleteIfExists(p.payload)
            _ <- deleteIfExists(p.root)
          yield ()
          StateT.liftF(remove.attempt.map(_.leftMap(e => RepositoryErr.IOError(e.getMessage.some))))
      }

    override def create(
        name: SecretName,
        meta: RawMetadata): StateF[F, Result[Unit]] =
      val p = SecretPathEntries.from(repoDir, name)

      StateT.liftF(checkSecretExists(p)).flatMap { rootExists =>
        if rootExists then StateT.pure(RepositoryErr.AlreadyExists(name).asLeft)
        else
          StateT.get[F, State].flatMap {
            case State.Encrypted(data) =>
              val store = for
                _ <- createDirectories(p.root)
                _ <- write(p.payload, data.byteArray, CreateOps)
                _ <- writeString(p.meta, meta.rawString, CreateOps)
              yield ()

              StateT.liftF(
                store.attempt.map(_.leftMap(e => RepositoryErr.IOError(e.getMessage.some)))
              )
            case _ => StateT.pure(RepositoryErr.CypherError.asLeft)
          }
      }

    override def update(
        name: SecretName,
        metadata: RawMetadata): StateF[F, Result[Unit]] =

      val p = SecretPathEntries.from(repoDir, name)

      StateT.liftF(checkSecretExists(p)).flatMap { rootExists =>
        if rootExists then StateT.pure(RepositoryErr.Corrupted(name).asLeft)
        else
          StateT.get[F, State].flatMap {
            case State.Encrypted(data) =>
              val update = for
                _ <- write(p.payload, data.byteArray, UpdateOps)
                _ <- writeString(p.meta, metadata.rawString, UpdateOps)
              yield ()
              StateT.liftF(
                update.attempt.map(_.leftMap(e => RepositoryErr.IOError(e.getMessage.some)))
              )
            case _ => StateT.pure(RepositoryErr.Undefined.asLeft)
          }
      }
  }

  class Crypted[F[_]: {Sync, Console}](cs: CypherService[F])
      extends RepositoryMutator[Mid[StateF[F, *], *]] {

    private def encrypt(state: State): EitherT[F, RepositoryErr, RawSecretData] =
      for
        rsd <- EitherT.fromEither {
                 state match
                   case Plain(data) => data.asRight
                   case _           => RepositoryErr.Undefined.asLeft
               }
        raw <- cs.encrypt(rsd.byteArray).liftE[RepositoryErr]
      yield RawSecretData.fromBytes(raw)

    override def create(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          state  <- StateT.get[F, State]
          encRes <- StateT.liftF(encrypt(state).value)
          res    <- encRes.fold(err => StateT.pure(err.asLeft),
                             enc => StateT.set[F, State](State.Encrypted(enc)) *> action
                 )
        yield res
      }

    override def update(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action =>
        for
          state  <- StateT.get[F, State]
          encRes <- StateT.liftF(encrypt(state).value)
          res    <- encRes.fold(err => StateT.pure(err.asLeft),
                             enc => StateT.set[F, State](State.Encrypted(enc)) *> action
                 )
        yield res

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] = identity

  }

  class Logging[F[_]: {Sync, Console as Out}] extends RepositoryMutator[Mid[StateF[F, *], *]] {

    override def create(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Out.println(s"Creating secret: $name"))
          res <- action
          _   <- StateT.liftF(Out.println(s"Creating secret: $name, ${res}"))
          _   <- StateT.liftF(
                 res.fold(err => Out.println(s"Failed to create secret [$name]: $err"),
                          _ => Out.println(s"Secret created: $name")
                 )
               )
        yield res
      }

    override def update(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Out.println(s"Updating secret: $name"))
          res <- action
          _   <- StateT.liftF(
                 res.fold(_ => Out.println(s"Secret updated: $name"),
                          err => Out.println(s"Failed to update secret [$name]: $err")
                 )
               )
        yield res
      }

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] =
      action => {
        for
          _   <- StateT.liftF(Out.println(s"Removing secret: $name"))
          res <- action
          _   <- StateT.liftF(
                 res.fold(_ => Out.println(s"Secret removed: $name"),
                          err => Out.println(s"Failed to remove secret [$name]: $err")
                 )
               )
        yield res
      }
  }

  class Gitted[F[_]: Sync](repoDir: RepoRootDir) extends RepositoryMutator[Mid[StateF[F, *], *]] {

    private def withGit(
        gitOp: GitRepo[F] => F[Either[GitError, RevCommit]]): Mid[StateF[F, *], Result[Unit]] =
      action =>
        val r = for
          st <- action.toEitherT
          _  <- StateT
                 .liftF(GitRepo.openExists(repoDir).use(gitOp(_)))
                 .liftE[RepositoryErr]
        yield ()

        r.value

    override def create(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] = {

      val locs      = SecretPathEntries.from(repoDir, name)
      val fileNames = NEL.of(locs.payload, locs.meta)

      withGit(_.commitFiles(fileNames, s"Add secret [$name]"))
    }

    override def update(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      val locs      = SecretPathEntries.from(repoDir, name)
      val fileNames = NEL.of(locs.payload, locs.meta)

      withGit(_.commitFiles(fileNames, s"Update secret [$name]"))

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] =
      val locs      = SecretPathEntries.from(repoDir, name)
      val fileNames = NEL.of(locs.payload, locs.meta)

      withGit(_.removeFiles(fileNames, s"Remove secret [$name]"))

  }

