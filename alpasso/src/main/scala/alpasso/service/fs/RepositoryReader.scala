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

import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.domain.{ Secret, SecretName }
import alpasso.service.cypher.{ CypherError, CypherService }
import alpasso.service.fs.model.*
import alpasso.service.git.{ GitError, GitRepo }

import glass.Upcast
import io.circe.*
import tofu.higherKind.*
import tofu.higherKind.Mid.*

enum RepositoryErr:
  case AlreadyExists(name: SecretName)
  case NotFound(name: SecretName)
  case Corrupted(name: SecretName)
  case Inconsistent(name: String)

  case Undefiled

object RepositoryErr:
  given Upcast[RepositoryErr, GitError]    = fromGitError
  given Upcast[RepositoryErr, CypherError] = _ => RepositoryErr.Inconsistent("Invalid cypher")

  def fromGitError(ge: GitError): RepositoryErr =
    ge match
      case GitError.RepositoryNotFound(path) =>
        RepositoryErr.Inconsistent("Git repository not initialized")
      case GitError.RepositoryIsDirty =>
        RepositoryErr.Inconsistent("Git repository has uncommited files")
      case GitError.UnexpectedError => RepositoryErr.Undefiled
      case GitError.SyncErr         => RepositoryErr.Undefiled

type Result[+T] = Either[RepositoryErr, T]
type PackResult[T] = Result[Secret[T]]

trait RepositoryReader[F[_]] derives ApplyK:
  def loadPayload(secret: Secret[Path]): F[PackResult[RawSecretData]]
  def loadMeta(secret: Secret[Path]): F[PackResult[RawMetadata]]
  def loadFully(secret: Secret[Locations]): F[PackResult[(RawSecretData, RawMetadata)]]
  def walkTree: F[Result[Node[Branch[Secret[Locations]]]]]

object RepositoryReader:

  def make[F[_]: { Async }](
      config: RepositoryConfiguration,
      cs: CypherService[F]): RepositoryReader[F] =
    val gitted: RepositoryReader[Mid[F, *]] = Gitted[F](config.repoDir)
    val lcs: RepositoryReader[Mid[F, *]]    = CypheredStorage[F](cs)
    (gitted |+| lcs) attach Impl[F](config.repoDir)

  class CypheredStorage[F[_]: { Async }](cs: CypherService[F]) extends RepositoryReader[Mid[F, *]] {

    override def loadPayload(
        secret: Secret[Path]): Mid[F, Result[Secret[RawSecretData]]] =
      action =>
        (for
          d <- EitherT(action)
          r <- cs.decrypt(d.payload.byteArray).liftE[RepositoryErr]
        yield d.copy(payload = RawSecretData.fromRaw(r))).value

    override def loadMeta(secret: Secret[Path]): Mid[F, PackResult[RawMetadata]] =
      identity

    override def loadFully(
        secret: Secret[
          Locations
        ]): Mid[F, Result[Secret[(RawSecretData, RawMetadata)]]] =
      action =>
        (for
          d <- EitherT(action)
          r <- cs.decrypt(d.payload._1.byteArray).liftE[RepositoryErr]
        yield d.copy(payload = (RawSecretData.fromRaw(r), d.payload._2))).value

    override def walkTree: Mid[F, Result[Node[Branch[Secret[Locations]]]]] =
      identity
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryReader[Mid[F, *]] {

    private val verifyGitRepo: EitherT[F, RepositoryErr, Unit] =
      GitRepo.openExists(repoDir).use(_.verify).liftE[RepositoryErr]

    override def loadPayload(secret: Secret[Path]): Mid[F, PackResult[RawSecretData]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadMeta(secret: Secret[Path]): Mid[F, PackResult[RawMetadata]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadFully(
        secret: Secret[
          Locations
        ]): Mid[F, PackResult[(RawSecretData, RawMetadata)]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def walkTree: Mid[F, Result[Node[Branch[Secret[Locations]]]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

  }

  class Impl[F[_]: Sync as F](repoDir: Path) extends RepositoryReader[F]:
    import F.blocking

    override def walkTree: F[Result[Node[Branch[Secret[Locations]]]]] =
      def mapBranch: Entry => Branch[Secret[Locations]] =
        case Entry(dir, Chain.nil) => Branch.Node(dir)
        case Entry(dir, files) =>
          (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
            case (Some(meta), Some(payload)) =>
              val name = SecretName.of(repoDir.relativize(dir).toString).toOption.get // todo
              Branch.Leaf(dir, Secret(name, Locations(payload, meta)))
            case _ => Branch.Node(dir)

      val exceptDir: Path => Boolean = p => p.endsWith(".git") || p.endsWith(".alpasso")

      for tree <- blocking(walkFileTree(repoDir, exceptDir))
      yield tree.traverse(v => Id(mapBranch(v))).asRight

    override def loadMeta(secret: Secret[Path]): F[PackResult[RawMetadata]] =
      val metaPath = secret.payload

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then RepositoryErr.Corrupted(secret.name).asLeft.pure[F]
        else
          for raw <- blocking(Files.readString(metaPath))
          yield RawMetadata
            .fromString(raw)
            .bimap(_ => RepositoryErr.Corrupted(secret.name), Secret(secret.name, _))
      }

    override def loadFully(secret: Secret[Locations]): F[PackResult[(RawSecretData, RawMetadata)]] =
      for
        p <- loadPayload(secret.map(_.secretData))
        m <- loadMeta(secret.map(_.metadata))
      yield (p, m).mapN((a, b) => Secret(a.name, (a.payload, b.payload)))

    override def loadPayload(secret: Secret[Path]): F[PackResult[RawSecretData]] =
      val path = secret.payload

      blocking(Files.exists(path)).flatMap { exists =>
        if !exists then RepositoryErr.Corrupted(secret.name).asLeft[Secret[RawSecretData]].pure[F]
        else
          for raw <- blocking(Files.readAllBytes(path))
          yield Secret(secret.name, RawSecretData.fromRaw(raw)).asRight
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

def mapBranch: Entry => Branch[Secret[Locations]] =
  case Entry(dir, Chain.nil) => Branch.Node(dir)
  case Entry(dir, files) =>
    (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
      case (Some(meta), Some(payload)) =>
        Branch
          .Leaf(
            dir,
            Secret(SecretName.of(dir.toString).toOption.get, Locations(payload, meta))
          ) // todo
      case _ => Branch.Node(dir)

@main
def main(): Unit =
  given Show[Entry] = Show.show(e => s"path = ${e.path}  [${e.files.toList.mkString(", ")}]")

  val tree = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  val bree: Node[Branch[Secret[Locations]]] = tree.traverse(a => Id(mapBranch(a)))

  val res: Option[Node[Branch[Secret[Locations]]]] = cutTree(bree, _ => true)

  given [A]: Show[Secret[A]] = Show.show(s => s"${s.name}")

  println(res.show)
