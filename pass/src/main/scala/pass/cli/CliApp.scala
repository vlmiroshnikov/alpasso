package pass.cli

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*
import scopt.{ OParser, RenderingMode }

import java.net.URL
import java.nio.file.*
import pass.core.model.*
import pass.core.model.given
import pass.cmdline.*
import pass.common.syntax.RejectionOr
import pass.service.fs.*
import pass.service.fs.model.Metadata

object CliApp extends IOApp:

  val repoDirDefault = Paths.get("", ".tmps").toAbsolutePath

  override def run(args: List[String]): IO[ExitCode] =
    val ls = LocalStorage.make[IO](repoDirDefault.toString)

    val cmd = Command.make[IO](ls)

    given [A: Show]: Show[Option[A]] =
      Show.show[Option[A]](_.fold("empty")(_.show))

    def handle[T: Show](fa: IO[RejectionOr[T]]): IO[Unit] =
      EitherT(fa).foldF(e => IO.println(s"Error: $e"), r => IO.println(r.show))

    val parser = ArgParser(repoDirDefault).parser
    val r = OParser.parse(parser, args, Action.Empty) match
      case Some(Action.InitWithPath(path)) => handle(cmd.initWithPath(path))
      case Some(Action.CreateSecret(Some(name), Some(payload), tags)) =>
        handle(cmd.create(name, payload, Metadata.of(tags)))
      case Some(Action.FindSecrets(filter, _)) =>
        handle(cmd.filter(filter.getOrElse(SecretFilter.All)))
      case Some(Action.Empty) => IO.println(OParser.usage(parser, RenderingMode.TwoColumns))
      case other              => IO.println(other.toString)

    r *> ExitCode.Success.pure[IO]
