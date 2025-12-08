package alpasso.cli

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.cli
import alpasso.cmdline.*
import alpasso.cmdline.view.*
import alpasso.cmdline.view.SessionView.given
import alpasso.cmdline.view.given
import alpasso.common.*
import alpasso.common.syntax.*
import alpasso.core.model.*
import alpasso.domain.{ SecretPayload, SensitiveMode }
import alpasso.service.fs.RepositoryConfigReader
import alpasso.service.fs.model.*

object AlpassoApp extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =

    val smgr = SessionManager.make[IO]
    val rmr  = RepositoryConfigReader.make[IO]

    given [A: Show]: Show[Option[A]] =
      Show.show[Option[A]](_.fold("empty")(_.show))

    given SensitiveMode = SensitiveMode.Masked

    def handle[T: Show](result: Result[T]): IO[ExitCode] =
      result match
        case Left(e)  => IO.println(e.into().show).as(ExitCode.Error)
        case Right(r) => IO.println(r.show).as(ExitCode.Success)

    def provideConfig[A](f: RepositoryConfiguration => IO[Result[A]]): IO[Result[A]] =
      (for
        session <- EitherT.fromOptionF(smgr.current, Err.UseSwitchCommand)
        cfg     <- rmr.read(session.path).liftE[Err]
        _       <- EitherT.cond(cfg.version == SemVer.current, (), Err.VersionMismatch(cfg.version))
        result  <- f(RepositoryConfiguration(session.path, cfg.version, cfg.cryptoAlg)).liftE[Err]
      yield result).value

    def provideCommand[A](f: Command[IO] => IO[Result[A]]): IO[Result[A]] =
      provideConfig(config => f(Command.make[IO](config)))

    ArgParser.command.parse(args) match
      case Left(help) =>
        handle(Err.CommandSyntaxError(help.toString).asLeft[Unit])

      case Right(Action.Repo(ops)) =>
        ops match
          case RepoOp.Init(pathOpt, cypher) =>
            val path = pathOpt.toAbsolutePath
            val boot = bootstrap[IO](path, SemVer.current, cypher)
              .flatTap(_.fold(_ => IO.unit, _ => smgr.setup(Session(path))))
            boot >>= handle

          case RepoOp.List => smgr.listAll.map(_.into().asRight[Err]) >>= handle
          case RepoOp.Log  => provideConfig(historyLog) >>= handle
          case RepoOp.Switch(sel) =>
            val switch = OptionT(smgr.listAll.map(_.zipWithIndex.find((_, idx) => idx == sel)))
              .cataF(
                IO(Err.UseSwitchCommand.asLeft),
                (s, _) => smgr.setup(s).as(s.into().asRight[Err])
              )
            switch >>= handle

          case RepoOp.RemoteOps(RemoteOp.Setup(name, url)) =>
            provideConfig(setupRemote(name, url)) >>= handle
          case RepoOp.RemoteOps(RemoteOp.Sync) =>
            provideConfig(syncRemote) >>= handle

      case Right(Action.New(sn, sp, sm)) =>
        provideCommand(_.create(sn, sp.getOrElse(SecretPayload.empty), sm)) >>= handle

      case Right(Action.Remove(sn)) =>
        provideCommand(_.remove(sn)) >>= handle

      case Right(Action.Patch(sn, spOpt, smOpt)) =>
        provideCommand(_.patch(sn, spOpt, smOpt)) >>= handle

      case Right(Action.Filter(where, OutputFormat.Tree, smode)) =>
        given SensitiveMode = smode
        provideCommand(_.filter(where)) >>= handle

      case Right(Action.Filter(where, OutputFormat.Table, smode)) =>
        given SensitiveMode = smode
        val res             = provideCommand(_.filter(where))
        val buildTableView = res
          .nested
          .nested
          .map { root =>
            val v = root.foldLeft(List.empty[SecretView]):
              case (agg, Branch.Node(_))    => agg
              case (agg, Branch.Leaf(_, a)) => agg :+ a
            TableView(v)
          }
          .value
          .value
        buildTableView >>= handle
