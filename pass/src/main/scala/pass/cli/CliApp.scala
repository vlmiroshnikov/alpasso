package pass.cli

import cats.*
import cats.syntax.all.*
import cats.effect.*
import cats.effect.IOApp.Simple
import scopt.OParser
import cats.effect.IOApp

opaque type Name <: String = String

enum Action:
  case CreatePassword(name: Name)
  case Empty

val builder = OParser.builder[Action]

import builder.*

val create = cmd("create")
  .text("create new password.")
  .children(
    arg[Name]("name")
      .required()
      .action((name, c) => Action.CreatePassword(name)),
    checkConfig(c => success)
    // if (c. && c.xyz) failure("xyz cannot keep alive")
  )

val p = OParser.sequence(
  programName("pass"),
  head("pass", "0.0.1"),
  create
)






object CliApp extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    val r = OParser.parse(p, args, Action.Empty) match
      case Some(Action.CreatePassword(name)) => IO.println(s"create [${name}]")
      case Some(Action.Empty)                => IO.println(s"empty")
      case None                              => IO.println("None")

    r *> ExitCode.Success.pure[IO]
