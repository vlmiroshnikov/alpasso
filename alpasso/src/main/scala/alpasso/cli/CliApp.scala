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
import alpasso.runDaemon
import alpasso.service.fs.*
import alpasso.service.fs.model.*
import alpasso.service.fs.repo.model.CryptoAlg
import alpasso.service.fs.repo.RepositoryMetadataReader
import alpasso.shared.SemVer

import logstage.{ IzLogger, Level, LogIO, StaticLogRouter }
import scopt.{ OParser, RenderingMode }

object CliApp extends IOApp:

  val repoDirDefault: Path = Paths.get(".local").toAbsolutePath

  override def run(args: List[String]): IO[ExitCode] =

    val logger = IzLogger(levels = Map("org.eclipse.jgit" -> Level.Info))

    given LogIO[IO] = LogIO.fromLogger(logger)
    // StaticLogRouter.instance.setup(logger.router)

    val ls  = LocalStorage.make[IO](repoDirDefault.toString)
    val rmr = RepositoryMetadataReader.make[IO](repoDirDefault)

    val cmd = Command.make[IO](ls)

    given [A: Show]: Show[Option[A]] =
      Show.show[Option[A]](_.fold("empty")(_.show))

    def handle[T: Show](fa: IO[RejectionOr[T]]): IO[Unit] =
      EitherT(fa).foldF(e => IO.println(s"Error: $e"), r => IO.println(r.show))

    val parser      = ArgParser(repoDirDefault).parser
    val maybeAction = IO(OParser.parse(parser, args, Action.Empty))
    val ctxOpt      = rmr.read

    val r = (maybeAction, rmr.read).flatMapN {
      case (Some(Action.InitWithPath(path)), left) =>
        //val gpg = CryptoAlg.Gpg("E59532DF27540224AF6A37CF0122EF2757E59DB9")
        val gpg = CryptoAlg.Gpg("64695F7D212F979D3553AFC5E0D6CE10FBEB0423")
        handle(cmd.initWithPath(path, SemVer.zero, gpg))
      case (Some(Action.CreateSecret(Some(name), Some(payload), tags)), _) =>
        handle(cmd.create(SecretName.of(name), payload, Metadata.of(tags)))

      case (Some(Action.UpdateSecret(Some(name), payload, tags)), _) =>
        handle(cmd.update(SecretName.of(name), payload, tags.map(Metadata.of)))

      case (Some(Action.FindSecrets(filter, format)), _) =>
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

      case (Some(Action.Daemon(_)), _) => runDaemon
      case v =>
        IO.println(v.toString) *> IO.println(OParser.usage(parser, RenderingMode.TwoColumns))
    }

    r *> ExitCode.Success.pure[IO]
