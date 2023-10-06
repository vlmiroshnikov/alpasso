package alpasso.cli

import java.nio.file.*

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cmdline.*
import alpasso.cmdline.view.*
import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.core.model.given
import alpasso.service.fs.*
import alpasso.service.fs.model.*

import scopt.{ OParser, RenderingMode }

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
        handle(cmd.create(SecretName.of(name), payload, Metadata.of(tags)))

      case Some(Action.UpdateSecret(Some(name), payload, tags)) =>
        handle(cmd.update(SecretName.of(name), payload, tags.map(Metadata.of)))

      case Some(Action.FindSecrets(filter, format)) =>
        format match
          case OutputFormat.Tree =>
            handle(cmd.filter(filter.getOrElse(SecretFilter.All)))
          case OutputFormat.Table =>
            val res = cmd.filter(filter.getOrElse(SecretFilter.All))
            val r1 = res
              .nested
              .nested
              .map { root =>
                val v = root.foldLeft(List.empty[SecretView]):
                  case (agg, Branch.Empty(_))    => agg
                  case (agg, Branch.Solid(_, a)) => agg :+ a
                TableView(v.mapWithIndex((s, i) => TableRowView(i, s)))
              }
              .value
              .value
            handle(r1)

      case v =>
        IO.println(v.toString) *> IO.println(OParser.usage(parser, RenderingMode.TwoColumns))

    r *> ExitCode.Success.pure[IO]
