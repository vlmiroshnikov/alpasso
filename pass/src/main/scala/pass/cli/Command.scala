package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*

import java.nio.ByteBuffer
import java.nio.file.{ Files, Path, StandardOpenOption }
import glass.*

import java.nio.charset.Charset
import io.circe.*
import io.circe.syntax.*

enum Err:
  case AlreadyExists(name: Name)
  case StorageNotInitialized(path: Path)
  case InconsistentStorage
  case InternalErr

object Err:
  given Upcast[Err, GitError]   = fromGitError(_)
  given Upcast[Err, StorageErr] = fromStorageErr(_)


  private def fromGitError(ge: GitError): Err =
    ge match
      case GitError.RepositoryNotFound(path) => Err.StorageNotInitialized(path)
      case GitError.RepositoryIsDirty        => Err.InconsistentStorage
      case GitError.UnexpectedError          => Err.InternalErr

  private def fromStorageErr(se: StorageErr): Err =
    se match
      case StorageErr.NotInitialized            => Err.InternalErr
      case StorageErr.DirAlreadyExists(_, name) => Err.AlreadyExists(name)

enum StorageErr:
  case NotInitialized
  case DirAlreadyExists(path: Path, name: Name)

case class Tag(name: String, value: String)

enum Metadata:
  case HostName(name: String)
  case UserData(tags: List[Tag])

object Metadata:

  given Encoder[Metadata] = Encoder.instance[Metadata]:
    case Metadata.HostName(name) => Json.obj("host-name" -> name.asJson)
    case Metadata.UserData(tags) => Json.obj(tags.map(t => t.name -> t.value.asJson): _*)

  given Decoder[Metadata] = Decoder.decodeJsonObject.emap { obj =>
    obj
      .toList
      .traverse((k, v) => (k.asRight, v.as[String]).mapN(Tag.apply))
      .map(Metadata.UserData.apply)
      .leftMap(_.getMessage)
  }

case class RawStoreEntry(secret: Path, meta: Path)

case class Secret[+T](name: Name, payload: T)

object Secret:

  given Functor[Secret] = new Functor[Secret]:

    override def map[A, B](fa: Secret[A])(f: A => B): Secret[B] =
      Secret(fa.name, f(fa.payload))

trait StorageCtx:
  def repoDir: Path
  def resolve(file: String): Path

opaque type Payload = Array[Byte]

object Payload:
  def from(bytes: Array[Byte]): Payload             = bytes
  extension (p: Payload) def byteArray: Array[Byte] = p

trait LocalStorage[F[_]]:
  def repoDir(): F[Either[StorageErr, Path]]
  def createFiles(name: Name, payload: Payload): F[Either[StorageErr, Secret[RawStoreEntry]]]
  def loadMeta(name: Name): F[Either[StorageErr, Secret[Option[Metadata]]]]

object LocalStorage:
  import fs2.io.file.Files as F2Files
  import fs2.io.file.Path as F2Path

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

    override def repoDir(): F[Either[StorageErr, Path]] = ctx.repoDir.asRight.pure

    override def loadMeta(name: Name): F[Either[StorageErr, Secret[Option[Metadata]]]] =
      val metaPath = ctx.resolve(name).resolve("meta")

      blocking(Files.exists(metaPath)).flatMap { exists =>
        if !exists then Secret(name, None).asRight.pure
        else
          for
            raw <- blocking(Files.readString(metaPath))
            meta <-
              blocking(parser.parse(raw).flatMap(_.as[Metadata]).getOrElse(Metadata.UserData(Nil)))
          yield Secret(name, meta.some).asRight
      }

    override def createFiles(name: Name, payload: Payload): F[Either[StorageErr, Secret[RawStoreEntry]]] =
      val path = ctx.resolve(name)

      blocking(Files.exists(path)).flatMap { exists =>
        if exists then StorageErr.DirAlreadyExists(path, name).asLeft.pure[F]
        else
          val metaPath    = path.resolve("meta")
          val payloadPath = path.resolve("payload")
          for
            _ <- blocking(Files.createDirectories(path))
            _ <- blocking(
                   Files.write(payloadPath,
                               payload.byteArray,
                               StandardOpenOption.CREATE_NEW,
                               StandardOpenOption.WRITE))
            _ <- blocking(
                   Files.writeString(metaPath,
                                     "",
                                     StandardOpenOption.CREATE_NEW,
                                     StandardOpenOption.WRITE))
          yield Secret(name, RawStoreEntry(payloadPath, metaPath)).asRight
      }

case class SecretView(name: Name, metadata: Metadata)

object SecretView:
  given Show[SecretView] = Show.show(s => s"secret: ${s.name} ${s.metadata.toString}")

case class ErrorView(code: String, explain: Option[String])

case class StorageView(repoDir: Path)

object StorageView:
  given Show[StorageView] = Show.show(s => s"storage: ${s.repoDir.toString}")

opaque type SecretPayload = Array[Byte]

object SecretPayload:
  private val utf8Charset                       = Charset.forName("UTF-8")
  def fromRaw(data: Array[Byte]): SecretPayload = data
  def fromString(data: String): SecretPayload   = data.getBytes(utf8Charset)

  extension (s: SecretPayload) def rawData: Array[Byte] = s

type RejectionOr[A] = Either[Err, A]

trait CryptoApi[F[_]]:
  def encrypt(data: ByteBuffer): F[ByteBuffer]

extension [F[_]: Functor, A, B](fa: F[Either[A, B]])
  def toEitherT: EitherT[F, A, B] = EitherT(fa)

  def liftTo[E](
      using
      up: Upcast[E, A]): EitherT[F, E, B] =
    EitherT(fa).leftMap(a => up.upcast(a))

enum SecretFilter:
  case Empty

trait Command[F[_]]:
  def initWithPath(repoDir: Path): F[RejectionOr[StorageView]]
  def create(name: Name, secret: SecretPayload): F[RejectionOr[SecretView]]
  def filter(filter: SecretFilter): F[RejectionOr[Tree[SecretView]]]

object Command:
  def make[F[_]: Async](ls: LocalStorage[F]): Command[F] = Impl[F](ls)

  class Impl[F[_]: Async](ls: LocalStorage[F]) extends Command[F]:

    override def initWithPath(repoDir: Path): F[RejectionOr[StorageView]] =
      GitRepo.create(repoDir).use(r => r.info.map(StorageView(_).asRight))

    override def filter(filter: SecretFilter): F[RejectionOr[Tree[SecretView]]] =
      def loadSecret(path: Path): EitherT[F, Err, Secret[Option[Metadata]]] =
        for
          meta <- ls.loadMeta(Name.of(path.toString)).liftTo[Err]
          _    <- EitherT.liftF(Sync[F].delay(println(path.toString)))
        yield meta

      val buildTree = for
        root <- ls.repoDir().liftTo[Err]
        tree <- EitherT.liftF(Sync[F].blocking(walkFileTree(root, exceptDir = _.endsWith(".git"))))
        treeView <- tree.traverse(p => loadSecret(root.relativize(p)))
      yield
        println(s"treeView = $treeView")
        val r = treeView
          .traverseFilter(s => Id(SecretView(s.name, Metadata.UserData(Nil)).some))
        println(s"TraverseF = $r")
        r
        // .traverse(s => Id(SecretView(s.name, s.payload.getOrElse(Metadata.UserData(Nil)))))

      buildTree.value

    override def create(name: Name, secret: SecretPayload): F[RejectionOr[SecretView]] =
      def addNewSecret(git: GitRepo[F]) = // todo use saga
        for
          _    <- git.verify().liftTo[Err]
          file <- ls.createFiles(name, Payload.from(secret.rawData)).liftTo[Err]
          _ <- git
                 .addFiles(NonEmptyList.of(file.payload.secret, file.payload.meta),
                           s"Create secret $name at ${sys.env.getOrElse("HOST", "")}")
                 .liftTo[Err]
        yield SecretView(file.name, Metadata.UserData(Nil))

      val result =
        for
          home <- ls.repoDir().liftTo[Err]
          r    <- GitRepo.openExists(home).use(addNewSecret(_).value).liftTo[Err]
        yield r

      result.value

end Command
