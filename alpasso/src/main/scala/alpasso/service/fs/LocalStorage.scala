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

import alpasso.core.model.*
import alpasso.service.fs.model.*

import io.circe.{ Decoder, Encoder, Json }

trait StorageCtx:
  def repoDir: Path
  def resolve(file: String): Path

trait LocalStorage[F[_]]:
  def repoDir(): F[StorageResult[Path]]
  def create(name: SecretName, payload: RawSecretData, meta: Metadata): F[StorageResult[Secret[RawStoreLocations]]]
  def update(name: SecretName, payload: RawSecretData, meta: Metadata): F[StorageResult[Secret[RawStoreLocations]]]

  def loadPayload(secret: Secret[Path]): F[StorageResult[Secret[RawSecretData]]]
  def loadMeta(secret: Secret[Path]): F[StorageResult[Secret[Metadata]]]
  def loadFully(secret: Secret[Path]): F[StorageResult[Secret[(RawSecretData, Metadata)]]]

  def walkTree: F[StorageResult[Node[Branch[Secret[RawStoreLocations]]]]]



object LocalStorage:

  def make[F[_]: Sync](home: String): LocalStorage[F] =
    val ctx = new StorageCtx:
      override def repoDir: Path = java.nio.file.Path.of(home)
      override def resolve(file: String): Path =
        java.nio.file.Path.of(home, file)
    Impl[F](ctx)

  class Impl[F[_]](
      using
      F: Sync[F]
    )(ctx: StorageCtx)
      extends LocalStorage[F]:

    import F.blocking
    import StandardOpenOption.*

    override def repoDir(): F[StorageResult[Path]] = ctx.repoDir.asRight.pure

    override def walkTree: F[StorageResult[Node[Branch[Secret[RawStoreLocations]]]]] =
      def mapBranch: Entry => Branch[Secret[RawStoreLocations]] =
        case Entry(dir, Chain.nil) => Branch.Empty(dir)
        case Entry(dir, files) =>
          (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
            case (Some(meta), Some(payload)) =>
              val name = SecretName.of(ctx.repoDir.relativize(dir).toString)
              Branch.Solid(dir, Secret(name, RawStoreLocations(payload, meta)))
            case _ => Branch.Empty(dir)

      for
        tree <- blocking(walkFileTree(ctx.repoDir, exceptDir = _.endsWith(".git")))
        tr = tree.traverse(v => Id(mapBranch(v)))
      yield tr.asRight

    override def loadMeta(secret: Secret[Path]): F[StorageResult[Secret[Metadata]]] =
      val metaPath = secret.payload

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then
          StorageErr.FileNotFound(metaPath, secret.name).asLeft[Secret[Metadata]].pure[F]
        else
          for raw <- blocking(Files.readString(metaPath))
          yield Metadata
            .fromString(raw)
            .bimap(_ => StorageErr.MetadataFileCorrupted(metaPath, secret.name),
                   Secret(secret.name, _)
            )
      }

    override def loadFully(secret: Secret[Path]): F[StorageResult[Secret[(RawSecretData, Metadata)]]] =
      for
        p <- loadPayload(secret)
        m <- loadMeta(secret)
      yield (p, m).mapN((a, b) => Secret(a.name, (a.payload, b.payload)))

    override def loadPayload(secret: Secret[Path]): F[StorageResult[Secret[RawSecretData]]] =
      val path = secret.payload

      blocking(Files.exists(path)).flatMap { exists =>
        if !exists then
          StorageErr.FileNotFound(path, secret.name).asLeft[Secret[RawSecretData]].pure[F]
        else
          for raw <- blocking(Files.readAllBytes(path))
          yield Secret(secret.name, RawSecretData.from(raw)).asRight
      }

    override def create(name: SecretName, payload: RawSecretData, meta: Metadata): F[StorageResult[Secret[RawStoreLocations]]] =
      val path = ctx.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(path) && Files.exists(payloadPath)).flatMap { rootExists =>
        if rootExists then StorageErr.DirAlreadyExists(path, name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.createDirectories(path))
            _ <- blocking(
                   Files.write(payloadPath, payload.byteArray, CREATE_NEW, WRITE)
                 )
            _ <- blocking(
                   Files.writeString(metaPath, meta.rawString, CREATE_NEW, WRITE)
                 )
          yield Secret(name, RawStoreLocations(payloadPath, metaPath)).asRight
      }

    override def update(name: SecretName, payload: RawSecretData, metadata: Metadata): F[StorageResult[Secret[RawStoreLocations]]] =
      val path = ctx.resolve(name)

      val metaPath    = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(path) && Files.exists(metaPath)).flatMap { exists =>
        if !exists then StorageErr.FileNotFound(metaPath, name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.write(payloadPath, payload.byteArray, StandardOpenOption.WRITE))
            _ <- blocking(Files.writeString(metaPath, metadata.rawString, CREATE, WRITE))
          yield Secret(name, RawStoreLocations(payloadPath, metaPath)).asRight
      }
end LocalStorage

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

import alpasso.core.model.given

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

def mapBranch: Entry => Branch[Secret[RawStoreLocations]] =
  case Entry(dir, Chain.nil) => Branch.Empty(dir)
  case Entry(dir, files) =>
    (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
      case (Some(meta), Some(payload)) =>
        Branch
          .Solid(dir, Secret(SecretName.of(dir.toString), RawStoreLocations(payload, meta)))
      case _ => Branch.Empty(dir)

@main
def main(): Unit =
  given Show[Entry] = Show.show(e => s"path = ${e.path}  [${e.files.toList.mkString(", ")}]")

  val tree = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  val bree: Node[Branch[Secret[RawStoreLocations]]] = tree.traverse(a => Id(mapBranch(a)))

  val res: Option[Node[Branch[Secret[RawStoreLocations]]]] = cutTree(bree, _ => true)

  given [A]: Show[Secret[A]] = Show.show(s => s"${s.name}")

  println(res.show)
