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
import alpasso.service.fs.*
import alpasso.service.fs.model.{Branch, given}
import alpasso.service.fs.repo.RepositoryConfigReader
import alpasso.service.fs.repo.model.{ CryptoAlg, RepositoryConfiguration }

import logstage.{IzLogger, Level, LogIO}

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

      case Right(Action.Filter(where, OutputFormat.Tree)) =>
        provideCommand(_.filter(where)) >>= handle

      case Right(Action.Filter(where, OutputFormat.Table)) =>
        val res = provideCommand(_.filter(where))
        val buildTableView = res
          .nested
          .nested
          .map { root =>
            val v = root.foldLeft(List.empty[SecretView]):
              case (agg, Branch.Empty(_)) => agg
              case (agg, Branch.Solid(_, a)) => agg :+ a
            TableView(v.mapWithIndex((s, i) => TableRowView(i, s)))
          }
          .value
          .value
        buildTableView >>= handle
