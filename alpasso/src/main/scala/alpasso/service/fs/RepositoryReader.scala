package alpasso.service.fs

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  FileVisitor,
  Files,
  OpenOption,
  Path,
  Paths,
  StandardOpenOption
}

import scala.collection.mutable

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*

import alpasso.common.Logger
import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.service.cypher
import alpasso.service.cypher.{CypherError, CypherService}
import alpasso.service.fs.model.*
import alpasso.service.git.{GitError, GitRepo}

import glass.Upcast
import io.circe.{ Decoder, Encoder, Json }
import logstage.LogIO
import tofu.higherKind.*
import tofu.higherKind.Mid.*

enum RepositoryErr:
  case AlreadyExists(name: SecretName)
  case NotFound(name: SecretName)
  case Corrupted(name: SecretName)
  case Inconsistent(name: String)

  case Undefiled

object RepositoryErr:
  given Upcast[RepositoryErr, GitError] = fromGitError
  given Upcast[RepositoryErr, CypherError] = _ => RepositoryErr.Inconsistent("Invalid cypher")

  def fromGitError(ge: GitError): RepositoryErr =
    ge match
      case GitError.RepositoryNotFound(path) =>
        RepositoryErr.Inconsistent("Git repository not initialized")
      case GitError.RepositoryIsDirty =>
        RepositoryErr.Inconsistent("Git repository has uncommited files")
      case GitError.UnexpectedError => RepositoryErr.Undefiled

type Result[+T] = Either[RepositoryErr, T]

trait RepositoryMutator[F[_]] derives ApplyK:
  def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[RawStoreLocations]]
  def update(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[RawStoreLocations]]
  def remove(name: SecretName): F[Result[RawStoreLocations]]

object RepositoryMutator:

  def make[F[_]: Async: Logger](config: RepositoryConfiguration): RepositoryMutator[F] =
    val gitted: RepositoryMutator[Mid[F, *]] = Gitted[F](config.repoDir)
    gitted attach Impl[F](config.repoDir)

  class Impl[F[_]: Logger: Sync as F](repoDir: Path) extends RepositoryMutator[F] {

    import F.blocking
    import Files.*
    import StandardOpenOption.*

    val CreateOps: List[OpenOption] = List(CREATE_NEW, WRITE)
    val UpdateOps: List[OpenOption] = List(CREATE, TRUNCATE_EXISTING, WRITE)

    override def remove(name: SecretName): F[Result[RawStoreLocations]] =
      val path = repoDir.resolve(name)

      val metaPath = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(exists(payloadPath)).flatMap { rootExists =>
        if !rootExists then RepositoryErr.NotFound(name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.deleteIfExists(metaPath))
            _ <- blocking(Files.deleteIfExists(payloadPath))
            _ <- blocking(Files.deleteIfExists(path)).whenA(Files.list(path).count() == 0)
          yield RawStoreLocations(payloadPath, metaPath).asRight
      }

    override def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[RawStoreLocations]] =
      val path = repoDir.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(exists(path) && exists(payloadPath)).flatMap { rootExists =>
        if rootExists then RepositoryErr.AlreadyExists(name).asLeft.pure[F]
        else
          for
            _ <- blocking(createDirectories(path))
            _ <- blocking(write(payloadPath, payload.byteArray, CreateOps *))
            _ <- blocking(writeString(metaPath, meta.rawString, CreateOps *))
          yield RawStoreLocations(payloadPath, metaPath).asRight
      }

    override def update(name: SecretName, payload: RawSecretData, metadata: RawMetadata): F[Result[RawStoreLocations]] =
      val path = repoDir.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(exists(path) && exists(metaPath)).flatMap { exists =>
        if !exists then RepositoryErr.Corrupted(name).asLeft.pure[F]
        else
          for
            _ <- blocking(write(payloadPath, payload.byteArray, UpdateOps *))
            _ <- blocking(writeString(metaPath, metadata.rawString, UpdateOps *))
          yield RawStoreLocations(payloadPath, metaPath).asRight
      }
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryMutator[Mid[F, *]] {

    override def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): Mid[F, Result[RawStoreLocations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Add secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }

    override def update(name: SecretName, payload: RawSecretData, meta: RawMetadata): Mid[F, Result[RawStoreLocations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Update secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }

    override def remove(name: SecretName): Mid[F, Result[RawStoreLocations]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Remove secret [$name]"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.secretData, locations.metadata)
            _ <- git.removeFiles(files, commitMsg).liftE[RepositoryErr]
          yield locations).value
        }
      }
  }

trait RepositoryReader[F[_]] derives ApplyK:
  def loadPayload(secret: SecretPackage[Path]): F[Result[SecretPackage[RawSecretData]]]
  def loadMeta(secret: SecretPackage[Path]): F[Result[SecretPackage[RawMetadata]]]
  def loadFully(secret: SecretPackage[RawStoreLocations]): F[Result[SecretPackage[(RawSecretData, RawMetadata)]]]
  def walkTree: F[Result[Node[Branch[SecretPackage[RawStoreLocations]]]]]

object RepositoryReader:

  def make[F[_]: Async: Logger](
      config: RepositoryConfiguration,
      cs: CypherService[F]): RepositoryReader[F] =
    val gitted: RepositoryReader[Mid[F, *]] = Gitted[F](config.repoDir)
    val lcs: RepositoryReader[Mid[F, *]]    = CypheredStorage[F](cs)
    (gitted |+| lcs) attach Impl[F](config.repoDir)

  class CypheredStorage[F[_]: Async: Logger](cs: CypherService[F])
      extends RepositoryReader[Mid[F, *]] {

    override def loadPayload(secret: SecretPackage[Path]): Mid[F, Result[SecretPackage[RawSecretData]]] =
      action =>
        (for
          d <- EitherT(action)
          r <- cs.decrypt(d.payload.byteArray).liftE[RepositoryErr]
        yield d.copy(payload = RawSecretData.from(r))).value

    override def loadMeta(secret: SecretPackage[Path]): Mid[F, Result[SecretPackage[RawMetadata]]] =
      identity

    override def loadFully(secret: SecretPackage[RawStoreLocations]): Mid[F, Result[SecretPackage[(RawSecretData, RawMetadata)]]] =
      action =>
        (for
          d <- EitherT(action)
          r <- cs.decrypt(d.payload._1.byteArray).liftE[RepositoryErr]
        yield d.copy(payload = (RawSecretData.from(r), d.payload._2))).value

    override def walkTree: Mid[F, Result[Node[Branch[SecretPackage[RawStoreLocations]]]]] =
      identity
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryReader[Mid[F, *]] {

    private val verifyGitRepo: EitherT[F, RepositoryErr, Unit] =
      GitRepo.openExists(repoDir).use(_.verify).liftE[RepositoryErr]

    override def loadPayload(secret: SecretPackage[Path]): Mid[F, Result[SecretPackage[RawSecretData]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadMeta(secret: SecretPackage[Path]): Mid[F, Result[SecretPackage[RawMetadata]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadFully(secret: SecretPackage[RawStoreLocations]): Mid[F, Result[SecretPackage[(RawSecretData, RawMetadata)]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def walkTree: Mid[F, Result[Node[Branch[SecretPackage[RawStoreLocations]]]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

  }

  class Impl[F[_]: Sync as F](repoDir: Path) extends RepositoryReader[F]:
    import F.blocking

    override def walkTree: F[Result[Node[Branch[SecretPackage[RawStoreLocations]]]]] =
      def mapBranch: Entry => Branch[SecretPackage[RawStoreLocations]] =
        case Entry(dir, Chain.nil) => Branch.Empty(dir)
        case Entry(dir, files) =>
          (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
            case (Some(meta), Some(payload)) =>
              val name = SecretName.of(repoDir.relativize(dir).toString).toOption.get // todo
              Branch.Solid(dir, SecretPackage(name, RawStoreLocations(payload, meta)))
            case _ => Branch.Empty(dir)

      val exceptDir: Path => Boolean = p => p.endsWith(".git") || p.endsWith(".alpasso")

      for tree <- blocking(walkFileTree(repoDir, exceptDir))
      yield tree.traverse(v => Id(mapBranch(v))).asRight

    override def loadMeta(secret: SecretPackage[Path]): F[Result[SecretPackage[RawMetadata]]] =
      val metaPath = secret.payload

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then RepositoryErr.Corrupted(secret.name).asLeft.pure[F]
        else
          for raw <- blocking(Files.readString(metaPath))
          yield RawMetadata
            .fromString(raw)
            .bimap(_ => RepositoryErr.Corrupted(secret.name), SecretPackage(secret.name, _))
      }

    override def loadFully(secret: SecretPackage[RawStoreLocations]): F[Result[SecretPackage[(RawSecretData, RawMetadata)]]] =
      for
        p <- loadPayload(secret.map(_.secretData))
        m <- loadMeta(secret.map(_.metadata))
      yield (p, m).mapN((a, b) => SecretPackage(a.name, (a.payload, b.payload)))

    override def loadPayload(secret: SecretPackage[Path]): F[Result[SecretPackage[RawSecretData]]] =
      val path = secret.payload

      blocking(Files.exists(path)).flatMap { exists =>
        if !exists then
          RepositoryErr.Corrupted(secret.name).asLeft[SecretPackage[RawSecretData]].pure[F]
        else
          for raw <- blocking(Files.readAllBytes(path))
            yield SecretPackage(secret.name, RawSecretData.from(raw)).asRight
      }

end RepositoryReader

case class Entry(path: Path, files: Chain[Path] = Chain.nil)

def walkFileTree(root: Path, exceptDir: Path => Boolean): Node[Entry] =
  val stub  = Node(Entry(Paths.get(".")))
  val stack = mutable.Stack[Node[Entry]](stub)
  val visitor: FileVisitor[Path] = new FileVisitor[Path]:
    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
      if exceptDir(dir) then FileVisitResult.SKIP_SUBTREE
      else
        val current = Node(Entry(dir))
        stack.push(current)
        FileVisitResult.CONTINUE

    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
      val top     = stack.pop()
      val entry   = top.data
      val updated = top.copy(data = entry.copy(files = entry.files.append(file)))
      stack.push(updated)
      FileVisitResult.CONTINUE

    override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
      FileVisitResult.CONTINUE

    override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
      val current = stack.pop()
      val top     = stack.pop()
      val updated = top.copy(siblings = top.siblings.append(current))
      stack.push(updated)
      FileVisitResult.CONTINUE

  Files.walkFileTree(root, visitor)
  val siblings = stack.head.siblings
  siblings.headOption.getOrElse(stub)

type Mark[A] = Either[A, A]

def cutTree[A](root: Node[Branch[A]], f: A => Boolean): Option[Node[Branch[A]]] =

  val marked = root.traverse(br => Id(br.fold(br.asLeft, sold => Either.cond(f(sold), br, br))))

  def filter_(root: Node[Mark[Branch[A]]]): Option[Node[Branch[A]]] =
    val sibs = root.siblings.traverseFilter(sib => Id(filter_(sib)))

    (sibs, root.data) match
      case (Chain.nil, Left(a))  => none
      case (Chain.nil, Right(a)) => Node(a, Chain.nil).some
      case (nonEmpty, Left(a))   => Node(a.toEmpty, nonEmpty).some
      case (nonEmpty, Right(a))  => Node(a, nonEmpty).some

  filter_(marked)

def mapBranch: Entry => Branch[SecretPackage[RawStoreLocations]] =
  case Entry(dir, Chain.nil) => Branch.Empty(dir)
  case Entry(dir, files) =>
    (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
      case (Some(meta), Some(payload)) =>
        Branch
          .Solid(
            dir,
            SecretPackage(SecretName.of(dir.toString).toOption.get,
              RawStoreLocations(payload, meta)
            )
          ) // todo
      case _ => Branch.Empty(dir)

@main
def main(): Unit =
  given Show[Entry] = Show.show(e => s"path = ${e.path}  [${e.files.toList.mkString(", ")}]")

  val tree = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  val bree: Node[Branch[SecretPackage[RawStoreLocations]]] = tree.traverse(a => Id(mapBranch(a)))

  val res: Option[Node[Branch[SecretPackage[RawStoreLocations]]]] = cutTree(bree, _ => true)

  given [A]: Show[SecretPackage[A]] = Show.show(s => s"${s.name}")

  println(res.show)
