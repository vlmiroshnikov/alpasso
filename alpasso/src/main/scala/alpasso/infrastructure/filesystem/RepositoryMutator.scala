package alpasso.infrastructure.filesystem

import java.nio.file.StandardOpenOption.*
import java.nio.file.{ Files, OpenOption, Path }

import cats.*
import cats.data.{ NonEmptyList as NEL, * }
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*

import alpasso.domain.{ Secret, SecretName }
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

  def make[F[_]: { Async }](
      config: RepositoryConfiguration,
      cs: CypherService[F]): RepositoryMutator[StateF[F, *]] =
    val gitted: RepositoryMutator[Mid[StateF[F, *], *]]  = Gitted[F](config.repoDir)
    val crypted: RepositoryMutator[Mid[StateF[F, *], *]] = Crypted[F](cs)
    (crypted |+| gitted).attach(Impl[F](config.repoDir))

  class Impl[F[_]: { Sync as F }](repoDir: RepoRootDir) extends RepositoryMutator[StateF[F, *]] {

    import FileEffects.*
    import alpasso.shared.syntax.*

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
            _          <- write(p.payload, encodedOpt.getOrElse(RawSecretData.empty).byteArray, CreateOps)
            _          <- writeString(p.meta, meta.rawString, CreateOps)
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
            _          <- write(p.payload, encodedOpt.getOrElse(RawSecretData.empty).byteArray, UpdateOps)
            _          <- writeString(p.meta, metadata.rawString, UpdateOps)
          yield ().asRight
      }
  }

  class Crypted[F[_]: Sync](cs: CypherService[F]) extends RepositoryMutator[Mid[StateF[F, *], *]] {

//      action => {
//        val result =
//          for enc <- cs.encrypt(s.payload.data.byteArray).liftE[RepositoryErr]
//          yield Secret(s.name, RawData(RawSecretData.fromRaw(enc), s.payload.meta))
//
//        result.value
//      }

    override def create(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {
        val result: StateF[F, Result[Unit]] = {
          for
            st  <- StateT.get[F, State]
            enc <- StateT.liftF(cs.encrypt(st.get.byteArray).liftE[RepositoryErr].value)
            _   <- StateT.set[F, State](enc.toOption.map(RawSecretData.fromRaw))
            res <- action
          yield res
        }

        result
      }

    override def update(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] = identity

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] = identity
  }

  class Gitted[F[_]: Sync](repoDir: RepoRootDir) extends RepositoryMutator[Mid[StateF[F, *], *]] {

//    override def create( s: Secret[RawData]): Mid[StateF[F, *], Result[Unit]] =
//      action => {
//        StateT.liftF(
//          GitRepo.openExists(repoDir).use { git =>
//            val commitMsg = s"Add secret [${s.name}]"
//
//            val locs = SecretPathEntries.from(repoDir, s.name)
//            (for
//              res <- EitherT(action)
//              _   <- git.commitFiles(NEL.of(locs.payload, locs.meta), commitMsg).liftE[RepositoryErr]
//            yield ()).value
//         }
//        )
//      }

    override def create(
        name: SecretName,
        meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] =
      action => {

        val commitMsg = s"Add secret [$name]"
        val locs      = SecretPathEntries.from(repoDir, name)

//        def run(act: StateF[F, Result[Unit]]): F[Either[RepositoryErr, Unit]] = GitRepo.openExists(repoDir).use { git =>
//
//          (for
//            _ <- act
//            _ <- git.commitFiles(NEL.of(locs.payload, locs.meta), commitMsg).liftE[RepositoryErr]
//          yield ()).value
//        }

        val fileNames = NEL.of(locs.payload, locs.meta)
        val r = for {
          st <- action.toEitherT
          _ <- StateT
                 .liftF(GitRepo.openExists(repoDir).use(_.commitFiles(fileNames, commitMsg)))
                 .liftE[RepositoryErr]
        } yield ()

        r.value
      }

    override def update(name: SecretName, meta: RawMetadata): Mid[StateF[F, *], Result[Unit]] = ???

    override def remove(name: SecretName): Mid[StateF[F, *], Result[Unit]] = ???
    //    override def update(
//        name: SecretName,
//        meta: RawMetadata): Mid[F,Result[Unit]] =
//      action => {
//          GitRepo.openExists(repoDir).use { git =>
//            val commitMsg = s"Update secret [$name]"
//            val locs      = SecretPathEntries.from(repoDir, name)
//            (for
//              _ <- EitherT(action)
//              _ <- git.commitFiles(NEL.of(locs.payload, locs.meta), commitMsg).liftE[RepositoryErr]
//            yield ()).value
//          }
//      }
//
//    override def remove(name: SecretName): Mid[F, Result[Unit]] =
//      action => {
//          GitRepo.openExists(repoDir).use { git =>
//            val commitMsg = s"Remove secret [$name]"
//            val locs      = SecretPathEntries.from(repoDir, name)
//            (for
//              _ <- EitherT(action)
//              _ <- git.removeFiles(NEL.of(locs.payload, locs.meta), commitMsg).liftE[RepositoryErr]
//            yield ()).value
//          }
//      }
  }
