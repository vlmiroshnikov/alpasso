package alpasso.service.fs

import java.io.IOException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, FileVisitor, Files, Path, Paths }

import scala.collection.mutable

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*
import cats.tagless.derived.*
import cats.tagless.syntax.*

import alpasso.cmdline.Err
import alpasso.common.syntax.*
import alpasso.common.{ Logger, Result }
import alpasso.core.model.*
import alpasso.service.cypher.CypherService
import alpasso.service.fs.model.*
import alpasso.service.git.GitRepo

import io.circe.{ Decoder, Encoder, Json }
import logstage.LogIO
import tofu.higherKind.*
import tofu.higherKind.Mid.*

trait RepositoryMutator[F[_]] derives ApplyK:
  def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[SecretPacket[RawStoreLocations]]]
  def update(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[SecretPacket[RawStoreLocations]]]

object RepositoryMutator:

  def make[F[_]: Async: Logger](config: RepositoryConfiguration): RepositoryMutator[F] =
    val gitted: RepositoryMutator[Mid[F, *]] = Gitted[F](config.repoDir)
    (gitted) attach Impl[F](config.repoDir)

  class Impl[F[_]: Logger: Sync as F](repoDir: Path) extends RepositoryMutator[F] {

    import F.blocking
    import StandardOpenOption.*

    override def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): F[Result[SecretPacket[RawStoreLocations]]] =
      val path = repoDir.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(path) && Files.exists(payloadPath)).flatMap { rootExists =>
        if rootExists then Err.AlreadyExists(name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.createDirectories(path))
            _ <- blocking(
                   Files.write(payloadPath, payload.byteArray, CREATE_NEW, WRITE)
                 )
            _ <- blocking(
                   Files.writeString(metaPath, meta.rawString, CREATE_NEW, WRITE)
                 )
          yield SecretPacket(name, RawStoreLocations(payloadPath, metaPath)).asRight
      }

    override def update(name: SecretName, payload: RawSecretData, metadata: RawMetadata): F[Result[SecretPacket[RawStoreLocations]]] =
      val path = repoDir.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(path) && Files.exists(metaPath)).flatMap { exists =>
        if !exists then Err.StorageCorrupted(metaPath).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.write(payloadPath, payload.byteArray, StandardOpenOption.WRITE))
            _ <- blocking(Files.writeString(metaPath, metadata.rawString, CREATE, WRITE))
          yield SecretPacket(name, RawStoreLocations(payloadPath, metaPath)).asRight
      }
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryMutator[Mid[F, *]] {

    override def create(name: SecretName, payload: RawSecretData, meta: RawMetadata): Mid[F, Result[SecretPacket[RawStoreLocations]]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Add secret $name"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.payload.secretData, locations.payload.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[Err]
          yield locations).value
        }
      }

    override def update(name: SecretName, payload: RawSecretData, meta: RawMetadata): Mid[F, Result[SecretPacket[RawStoreLocations]]] =
      action => {
        GitRepo.openExists(repoDir).use { git =>
          val commitMsg = s"Update secret $name"
          (for
            locations <- EitherT(action)
            files = NonEmptyList.of(locations.payload.secretData, locations.payload.metadata)
            _ <- git.commitFiles(files, commitMsg).liftE[Err]
          yield locations).value
        }
      }

  }

trait RepositoryReader[F[_]] derives ApplyK:
  def loadPayload(secret: SecretPacket[Path]): F[Result[SecretPacket[RawSecretData]]]
  def loadMeta(secret: SecretPacket[Path]): F[Result[SecretPacket[RawMetadata]]]
  def loadFully(secret: SecretPacket[RawStoreLocations]): F[Result[SecretPacket[(RawSecretData, RawMetadata)]]]
  def walkTree: F[Result[Node[Branch[SecretPacket[RawStoreLocations]]]]]

object RepositoryReader:

  def make[F[_]: Async: Logger](
      config: RepositoryConfiguration,
      cs: CypherService[F]): RepositoryReader[F] =
    val gitted: RepositoryReader[Mid[F, *]] = Gitted[F](config.repoDir)
    val lcs: RepositoryReader[Mid[F, *]]    = CypheredStorage[F](cs)
    (gitted |+| lcs) attach Impl[F](config.repoDir)

  class CypheredStorage[F[_]: Async: Logger](cs: CypherService[F])
      extends RepositoryReader[Mid[F, *]] {

    override def loadPayload(secret: SecretPacket[Path]): Mid[F, Result[SecretPacket[RawSecretData]]] =
      action =>
        (for
          d <- EitherT(action)
          r <- cs.decrypt(d.payload.byteArray).liftE[Err]
        yield d.copy(payload = RawSecretData.from(r))).value

    override def loadMeta(secret: SecretPacket[Path]): Mid[F, Result[SecretPacket[RawMetadata]]] =
      identity

    override def loadFully(secret: SecretPacket[RawStoreLocations]): Mid[F, Result[SecretPacket[(RawSecretData, RawMetadata)]]] =
      action =>
        (for
          d: SecretPacket[(RawSecretData, RawMetadata)] <- EitherT(action)
          r <- cs.decrypt(d.payload._1.byteArray).liftE[Err]
        yield d.copy(payload = (RawSecretData.from(r), d.payload._2))).value

    override def walkTree: Mid[F, Result[Node[Branch[SecretPacket[RawStoreLocations]]]]] =
      identity
  }

  class Gitted[F[_]: Sync](repoDir: Path) extends RepositoryReader[Mid[F, *]] {

    private val verifyGitRepo: EitherT[F, Err, Unit] =
      GitRepo.openExists(repoDir).use(_.verify).liftE[Err]

    override def loadPayload(secret: SecretPacket[Path]): Mid[F, Result[SecretPacket[RawSecretData]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadMeta(secret: SecretPacket[Path]): Mid[F, Result[SecretPacket[RawMetadata]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def loadFully(secret: SecretPacket[RawStoreLocations]): Mid[F, Result[SecretPacket[(RawSecretData, RawMetadata)]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

    override def walkTree: Mid[F, Result[Node[Branch[SecretPacket[RawStoreLocations]]]]] =
      action => verifyGitRepo.flatMapF(_ => action).value

  }

  class Impl[F[_]: Sync as F](repoDir: Path) extends RepositoryReader[F]:

    import F.blocking
    import StandardOpenOption.*

    override def walkTree: F[Result[Node[Branch[SecretPacket[RawStoreLocations]]]]] =
      def mapBranch: Entry => Branch[SecretPacket[RawStoreLocations]] =
        case Entry(dir, Chain.nil) => Branch.Empty(dir)
        case Entry(dir, files) =>
          (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
            case (Some(meta), Some(payload)) =>
              val name = SecretName.of(repoDir.relativize(dir).toString).toOption.get // todo
              Branch.Solid(dir, SecretPacket(name, RawStoreLocations(payload, meta)))
            case _ => Branch.Empty(dir)

      val exceptDir: Path => Boolean = p => p.endsWith(".git") || p.endsWith(".repo")

      for tree <- blocking(walkFileTree(repoDir, exceptDir))
      yield tree.traverse(v => Id(mapBranch(v))).asRight

    override def loadMeta(secret: SecretPacket[Path]): F[Result[SecretPacket[RawMetadata]]] =
      val metaPath = secret.payload

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then Err.StorageCorrupted(metaPath).asLeft[SecretPacket[RawMetadata]].pure[F]
        else
          for raw <- blocking(Files.readString(metaPath))
          yield RawMetadata
            .fromString(raw)
            .bimap(_ => Err.StorageCorrupted(metaPath), SecretPacket(secret.name, _))
      }

    override def loadFully(secret: SecretPacket[RawStoreLocations]): F[Result[SecretPacket[(RawSecretData, RawMetadata)]]] =
      for
        p <- loadPayload(secret.map(_.secretData))
        m <- loadMeta(secret.map(_.metadata))
      yield (p, m).mapN((a, b) => SecretPacket(a.name, (a.payload, b.payload)))

    override def loadPayload(secret: SecretPacket[Path]): F[Result[SecretPacket[RawSecretData]]] =
      val path = secret.payload

      blocking(Files.exists(path)).flatMap { exists =>
        if !exists then Err.StorageCorrupted(path).asLeft[SecretPacket[RawSecretData]].pure[F]
        else
          for raw <- blocking(Files.readAllBytes(path))
          yield SecretPacket(secret.name, RawSecretData.from(raw)).asRight
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

def mapBranch: Entry => Branch[SecretPacket[RawStoreLocations]] =
  case Entry(dir, Chain.nil) => Branch.Empty(dir)
  case Entry(dir, files) =>
    (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
      case (Some(meta), Some(payload)) =>
        Branch
          .Solid(
            dir,
            SecretPacket(SecretName.of(dir.toString).toOption.get, RawStoreLocations(payload, meta))
          ) // todo
      case _ => Branch.Empty(dir)

@main
def main(): Unit =
  given Show[Entry] = Show.show(e => s"path = ${e.path}  [${e.files.toList.mkString(", ")}]")

  val tree = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  val bree: Node[Branch[SecretPacket[RawStoreLocations]]] = tree.traverse(a => Id(mapBranch(a)))

  val res: Option[Node[Branch[SecretPacket[RawStoreLocations]]]] = cutTree(bree, _ => true)

  given [A]: Show[SecretPacket[A]] = Show.show(s => s"${s.name}")

  println(res.show)
