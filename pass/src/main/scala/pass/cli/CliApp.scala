package pass.cli

import cats.*
import cats.data.*
import cats.data.NonEmptyChain
import cats.syntax.all.*
import cats.effect.*
import cats.effect.IOApp.Simple
import scopt.OParser
import cats.effect.IOApp

import java.net.URL
import java.nio.file.*

opaque type Name <: String = String

object Name:
  def of(name: String): Name = name

enum Action:
  case InitWithPath(repoDir: Path)
  case InitFromRepository(url: URL)
  case CreateSecret(name: Option[Name], secret: Option[SecretPayload])
  case ListSecrets(filter: SecretFilter)
  case Empty

val builder = OParser.builder[Action]

import builder.*

val repoDirDefault = Paths.get("", ".tmps").toAbsolutePath

val create = cmd("create")
  .text("create new secret.")
  .children(
    arg[Name]("name")
      .required()
      .action {
        case (name, a: Action.CreateSecret) => a.copy(name = name.some)
        case (name, _)                      => Action.CreateSecret(name.some, none)
      },
    arg[String]("secret")
      .required()
      .action {
        case (s, a: Action.CreateSecret) => a.copy(secret = SecretPayload.fromString(s).some)
        case (s, _) => Action.CreateSecret(name = none, secret = SecretPayload.fromString(s).some)
      },
    checkConfig {
      case a: Action.CreateSecret if a.name.nonEmpty && a.secret.nonEmpty => success
      case a: Action.CreateSecret                                         => failure(s"fail $a")
      case other                                                          => success
    }
  )

val init = cmd("init")
  .text("init repository")
  .children(
    opt[Path]("path")
      .withFallback(() => repoDirDefault)
      .action((path, c) => Action.InitWithPath(path)),
    checkConfig(c => success)
  )

val list = cmd("list")
  .text("list")
  .abbr("ls")
  .children(
    arg[String]("string")
     .action((str, c) => Action.ListSecrets(SecretFilter.Empty)),
  )


val p = OParser.sequence(
  programName("pass"),
  head("pass", "0.0.1"),
  init,
  create,
  list
)

object CliApp extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    given fs2.io.file.Files[IO] = fs2.io.file.Files.forAsync[IO]

    val ls = LocalStorage.make[IO](repoDirDefault.toString)

    val cmd = Command.make[IO](ls)

    def handle[T](fa: IO[RejectionOr[T]]): IO[Unit] =
      EitherT(fa).foldF(e => IO.println(s"Error: $e"), r => IO.println(r))

    val r = OParser.parse(p, args, Action.Empty) match
      case Some(Action.InitWithPath(path))                     => handle(cmd.initWithPath(path))
      case Some(Action.CreateSecret(Some(name), Some(secret))) => handle(cmd.create(name, secret))
      case Some(Action.ListSecrets(filter))                    => handle(cmd.filter(filter))
      case Some(Action.Empty)                                  => IO.println(s"empty")
      case other                                               => IO.println(other.toString)

    r *> ExitCode.Success.pure[IO]
