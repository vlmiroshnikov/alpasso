package pass.service.fs

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*
import io.circe.{ Decoder, Encoder, Json }
import pass.core.model.*
import pass.service.fs.model.*

import java.io.IOException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, FileVisitor, Files, Path, Paths }
import scala.collection.mutable

trait StorageCtx:
  def repoDir: Path
  def resolve(file: String): Path

trait LocalStorage[F[_]]:
  def repoDir(): F[StorageResult[Path]]
  def createFiles(secret: Secret[(Payload, Metadata)]): F[StorageResult[Secret[RawStoreEntry]]]
  def loadMeta(secret: Secret[RawStoreEntry]): F[StorageResult[Secret[Metadata]]]
  def walkTree: F[StorageResult[Node[Branch[Secret[RawStoreEntry]]]]]

enum Branch[+A]:
  case Empty(path: Path)
  case Solid(path: Path, data: A)

object Branch:

  extension [A](b: Branch[A])

    def toEmpty: Branch[A] =
      b match
        case a @ Empty(_)   => a
        case Solid(path, _) => Empty(path)

    def fold[B](empty: => B, solid: A => B): B =
      b match
        case Branch.Empty(_)       => empty
        case Branch.Solid(_, data) => solid(data)

  given [A: Show]: Show[Branch[A]] = Show.show:
    case Branch.Empty(path)       => path.getFileName.toString
    case Branch.Solid(path, data) => data.show

  given Functor[Branch] = new Functor[Branch]:

    override def map[A, B](fa: Branch[A])(f: A => B): Branch[B] =
      fa match
        case Empty(dir)        => Branch.Empty(dir)
        case Solid(path, data) => Branch.Solid(path, f(data))

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

    override def repoDir(): F[StorageResult[Path]] = ctx.repoDir.asRight.pure

    override def walkTree: F[StorageResult[Node[Branch[Secret[RawStoreEntry]]]]] =
      def mapBranch: Entry => Branch[Secret[RawStoreEntry]] =
        case Entry(dir, Chain.nil) => Branch.Empty(dir)
        case Entry(dir, files) =>
          (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
            case (Some(meta), Some(payload)) =>
              val name = SecretName.of(ctx.repoDir.relativize(dir).toString)
              Branch.Solid(dir, Secret(name, RawStoreEntry(payload, meta)))
            case _ => Branch.Empty(dir)

      for
        tree <- blocking(walkFileTree(ctx.repoDir, exceptDir = _.endsWith(".git")))
        tr = tree.traverse(v => Id(mapBranch(v)))
      yield tr.asRight

    override def loadMeta(secret: Secret[RawStoreEntry]): F[StorageResult[Secret[Metadata]]] =
      val metaPath = secret.payload.meta

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then
          StorageErr.FileNotFound(metaPath, secret.name).asLeft[Secret[Metadata]].pure[F]
        else
          for
            raw  <- blocking(Files.readString(metaPath))
          yield Metadata.fromString(raw)
                        .bimap(_ => StorageErr.MetadataFileCorrupted(metaPath,secret.name), Secret(secret.name, _))

      }

    override def createFiles(secret: Secret[(Payload, Metadata)]): F[StorageResult[Secret[RawStoreEntry]]] =
      val path    = ctx.resolve(secret.name)

      val payload = secret.payload._1
      val metadata = secret.payload._2

      val metaPath = path.resolve("meta")
      val payloadPath = path.resolve("payload")

      blocking(Files.exists(path) && Files.exists(metaPath)).flatMap { exists =>
        if exists then StorageErr.DirAlreadyExists(metaPath, secret.name).asLeft.pure[F]
        else
          for
            _ <- blocking(Files.createDirectories(path))
            _ <- blocking(
                   Files.write(payloadPath,
                               payload.byteArray,
                               StandardOpenOption.CREATE_NEW,
                               StandardOpenOption.WRITE))
            _ <- blocking(
                   Files.writeString(metaPath,
                                     metadata.rawString,
                                     StandardOpenOption.CREATE_NEW,
                                     StandardOpenOption.WRITE))
          yield Secret(secret.name, RawStoreEntry(payloadPath, metaPath)).asRight
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

import pass.core.model.given

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

def mapBranch: Entry => Branch[Secret[RawStoreEntry]] =
  case Entry(dir, Chain.nil) => Branch.Empty(dir)
  case Entry(dir, files) =>
    (files.find(_.endsWith("meta")), files.find(_.endsWith("payload"))) match
      case (Some(meta), Some(payload)) =>
        Branch
          .Solid(dir, Secret(SecretName.of(dir.toString), RawStoreEntry(payload, meta)))
      case _ => Branch.Empty(dir)

@main
def main =
  given Show[Entry] = Show.show(e => s"path = ${e.path}  [${e.files.toList.mkString(", ")}]")

  val tree = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  val bree: Node[Branch[Secret[RawStoreEntry]]] = tree.traverse(a => Id(mapBranch(a)))

  val res: Option[Node[Branch[Secret[RawStoreEntry]]]] = cutTree(bree, _ => true)

  given [A]: Show[Secret[A]] = Show.show(s => s"${s.name}")

  println(res.show)
