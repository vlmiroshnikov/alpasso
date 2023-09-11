package pass.cli

import cats.*
import cats.data.*
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
  case CreateSecret(name: Name, repoDir: Path)
  case Empty

val builder = OParser.builder[Action]

import builder.*

val repoDirDefault = Paths.get(".", "/.tmps").toAbsolutePath

val create = cmd("create")
  .text("create new secret.")
  .children(
    arg[Name]("name")
      .required()
      .action((name, c) => Action.CreateSecret(name, repoDirDefault)),
    arg[Path]("repo")
      .optional()
      .action {
        case (repoDir, Action.CreateSecret(name, _)) => Action.CreateSecret(name, repoDir)
        case (repoDir, action)                       => Action.Empty
      },
    checkConfig(c => success)
    // if (c. && c.xyz) failure("xyz cannot keep alive")
  )

val init = cmd("init")
  .text("init repository")
  .children(
    opt[Path]("path")
      .withFallback(() => {

        repoDirDefault
      })
      .action((path, c) => Action.InitWithPath(path)),
    checkConfig(c => success)
  )

val p = OParser.sequence(
  programName("pass"),
  head("pass", "0.0.1"),
  create,
  init
)

object CliApp extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    given fs2.io.file.Files[IO] = fs2.io.file.Files.forAsync[IO]

    given LocalStorage[IO] = LocalStorage.make[IO](repoDirDefault.toString)

    val cmd = Command.make[IO]

    def handle[T](fa: IO[RejectionOr[T]]): IO[Unit] =
      EitherT(fa).foldF(e => IO.println(s"Error: ${e}"), r => IO.println(r))

    val r = OParser.parse(p, args, Action.Empty) match
      case Some(Action.InitWithPath(path)) => handle(cmd.initWithPath(path))
      case Some(Action.CreateSecret(name, path)) =>
        handle(cmd.create(name))
      case Some(Action.Empty) => IO.println(s"empty")
      case None               => IO.println("None")

    r *> ExitCode.Success.pure[IO]
