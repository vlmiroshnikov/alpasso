package alpasso.cli

import java.nio.file.*

import scala.annotation.experimental

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cmdline.*
import alpasso.cmdline.view.*
import alpasso.common.syntax.*
import alpasso.common.{ Result, SemVer }
import alpasso.core.model.*
import alpasso.core.model.given
import alpasso.service.fs.*
import alpasso.service.fs.model.*
import alpasso.service.fs.repo.RepositoryConfigReader
import alpasso.service.fs.repo.model.{ CryptoAlg, RepositoryConfiguration }

import logstage.{ IzLogger, Level, LogIO, StaticLogRouter }
import scopt.{ OParser, RenderingMode }

@experimental
object CliApp extends IOApp:

  val repoDirDefault: Path = Paths.get(".local").toAbsolutePath

  override def run(args: List[String]): IO[ExitCode] =

    val logger = IzLogger(levels = Map("org.eclipse.jgit" -> Level.Info))

    given LogIO[IO] = LogIO.fromLogger(logger)
    // StaticLogRouter.instance.setup(logger.router)

    val smgr = SessionManager.make[IO]
    val rmr  = RepositoryConfigReader.make[IO]

    given [A: Show]: Show[Option[A]] =
      Show.show[Option[A]](_.fold("empty")(_.show))

    def handle[T: Show](result: Result[T]): IO[ExitCode] =
      result match
        case Left(e)  => IO.println(s"Error: $e").as(ExitCode.Error)
        case Right(r) => IO.println(r.show).as(ExitCode.Success)

    val maybeAction = IO(ArgParser.command.parse(args))

    def provideCommand[A](f: Command[IO] => IO[Result[A]]): IO[Result[A]] =
      (for
        session <- EitherT.fromOptionF(smgr.current(), Err.UseSwitchCommand)
        cfg     <- rmr.read(session.path).liftE[Err]
        configuration = RepositoryConfiguration(session.path, cfg.version, cfg.cryptoAlg)
        result <- f(Command.make[IO](configuration)).liftE[Err]
      yield result).value

    ArgParser.command.parse(args) match
      case Left(help) =>
        handle(Err.CommandSyntaxError(help.toString).asLeft[Unit])

      case Right(Action.Init(pathOpt, gpg)) =>
        val path = pathOpt.getOrElse(Path.of(".local").toAbsolutePath)
        (bootstrap[IO](path, SemVer.zero, gpg) <* smgr.append(Session(path))) >>= handle

      case Right(Action.New(sn, sp, sm)) =>
        provideCommand(_.create(sn, sp.getOrElse(SecretPayload.empty), sm)) >>= handle

    //    val r = (maybeAction, rmr.read).flatMapN {
    //      case (Some(Action.Init(path)), left) =>
    //
    //
    //      case (Some(Action.CreateSecret(Some(name), Some(payload), tags)), Right(cfg)) =>
    //        val c   = RepositoryConfiguration(repoDirDefault, cfg.version, cfg.cryptoAlg)
    //        val cmd = Command.make[IO](c)
    //        handle(cmd.create(SecretName.of(name), payload, Metadata.of(tags)))
    //
    //      case (Some(Action.UpdateSecret(Some(name), payload, tags)), Right(cfg)) =>
    //        val c   = RepositoryConfiguration(repoDirDefault, cfg.version, cfg.cryptoAlg)
    //        val cmd = Command.make[IO](c)
    //        handle(cmd.update(SecretName.of(name), payload, tags.map(Metadata.of)))
    //
    //      case (Some(Action.FindSecrets(filter, format)), Right(cfg)) =>
    //        val c   = RepositoryConfiguration(repoDirDefault, cfg.version, cfg.cryptoAlg)
    //        val cmd = Command.make[IO](c)
    //        format match
    //          case OutputFormat.Tree =>
    //            handle(cmd.filter(filter.getOrElse(SecretFilter.All)))
    //          case OutputFormat.Table =>
    //            val res = cmd.filter(filter.getOrElse(SecretFilter.All))
    //            val r1 = res
    //              .nested
    //              .nested
    //              .map { root =>
    //                val v = root.foldLeft(List.empty[SecretView]):
    //                  case (agg, Branch.Empty(_))    => agg
    //                  case (agg, Branch.Solid(_, a)) => agg :+ a
    //                TableView(v.mapWithIndex((s, i) => TableRowView(i, s)))
    //              }
    //              .value
    //              .value
    //            handle(r1)
    //
    //      case v => IO.println(v.toString)
    //    }
