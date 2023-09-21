package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*
import scopt.OParser

import java.net.URL
import java.nio.file.*
import pass.core.model.*
import pass.core.model.given

import pass.cmdline.*
import pass.common.syntax.RejectionOr
import pass.service.fs.*

object CliApp extends IOApp:

  val repoDirDefault = Paths.get("", ".tmps").toAbsolutePath

  override def run(args: List[String]): IO[ExitCode] =
    val ls = LocalStorage.make[IO](repoDirDefault.toString)

    val cmd = Command.make[IO](ls)

    def handle[T: Show](fa: IO[RejectionOr[T]]): IO[Unit] =
      EitherT(fa).foldF(e => IO.println(s"Error: $e"), r => IO.println(s"Result: \n${r.show}"))

    val r = OParser.parse(ArgParser(repoDirDefault).parser, args, Action.Empty) match
      case Some(Action.InitWithPath(path))                     => handle(cmd.initWithPath(path))
      case Some(Action.CreateSecret(Some(name), Some(payload))) => handle(cmd.create(Secret(name, payload)))
      case Some(Action.ListSecrets(filter))                    => handle(cmd.filter(filter))
      case Some(Action.Empty)                                  => IO.println(s"empty")
      case other                                               => IO.println(other.toString)

    r *> ExitCode.Success.pure[IO]
