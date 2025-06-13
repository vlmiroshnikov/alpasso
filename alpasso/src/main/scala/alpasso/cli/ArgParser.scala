package alpasso.cli

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

import alpasso.cmdline.view.{ OutputFormat, SecretFilter }
import alpasso.core.model.{SecretMetadata, SecretName, SecretPayload, SensitiveMode}
import alpasso.service.cypher.CypherAlg

import com.monovore.decline.*

enum RemoteOp:
  case Setup(name: String, url: String)
  case Sync

enum RepoOp:
  case Init(path: Path, cypher: CypherAlg)
  case List
  case Switch(index: Int)
  case Log
  case RemoteOps(ops: RemoteOp)

enum Action:
  case Repo(ops: RepoOp)
  case New(name: SecretName, secret: Option[SecretPayload], meta: Option[SecretMetadata])
  case Patch(name: SecretName, payload: Option[SecretPayload], meta: Option[SecretMetadata])
  case Filter(where: SecretFilter, format: OutputFormat, sensetiveMode: SensitiveMode)
  case Remove(name: SecretName)

object ArgParser:

  val repos: Opts[Action] = Opts.subcommand("repo", "Repositories ops") {

    val init = Opts.subcommand("init", "Init new repository") {
      val path = Opts.option[Path]("path", "Repository path", "p")
      val gpg  = Opts.option[String]("gpg-fingerprint", "GPG fingerprint").map(CypherAlg.Gpg(_))

      (path, gpg).mapN(RepoOp.Init.apply)
    }

    val list = Opts.subcommand("list", "List repository") {
      Opts.apply(RepoOp.List)
    }

    val switch = Opts.subcommand("switch", "switch repository") {
      val path = Opts.argument[Int]("index")
      path.map(RepoOp.Switch.apply)
    }

    val log = Opts.subcommand("log", "log repository") {
      Opts.apply(RepoOp.Log)
    }

    val remote = Opts.subcommand("remote", "remote ops") {
      val setup = Opts.subcommand("setup", "add origin remote repository") {
        val url  = Opts.argument[String]("url")
        val name = Opts.argument[String]("name").withDefault("origin")
        (name, url).mapN(RemoteOp.Setup.apply)
      }

      val sync = Opts.subcommand("sync", "sync with remote repository") {
        Opts.apply(RemoteOp.Sync)
      }

      (setup orElse sync).map(RepoOp.RemoteOps.apply)
    }

    List(init, list, switch, log, remote).combineAll.map(Action.Repo.apply)
  }

  val add: Opts[Action] = Opts.subcommand("new", "Add new secret") {
    val name   = Opts.argument[String]("name").mapValidated(SecretName.of)
    val secret = Opts.argument[String]("secret").map(SecretPayload.fromString).orNone
    val tags = Opts
      .option[String]("meta", "k1=v1,k2=v2")
      .mapValidated[SecretMetadata](SecretMetadata.fromRaw)
      .orNone

    (name, secret, tags).mapN(Action.New.apply)
  }

  val patch: Opts[Action] = Opts.subcommand("patch", "Update exists secret") {
    val name   = Opts.argument[String]("name").mapValidated(SecretName.of)
    val secret = Opts.argument[String]("secret").map(SecretPayload.fromString).orNone
    val tags = Opts
      .option[String]("meta", "k1=v1,k2=v2")
      .mapValidated[SecretMetadata](SecretMetadata.fromRaw)
      .orNone

    (name, secret, tags).mapN(Action.Patch.apply)
  }

  val list: Opts[Action] = Opts.subcommand("ls", "List secrets") {
    val grep = Opts.option[String]("grep", "Grep expression").map(SecretFilter.Grep.apply).orNone
    val output = Opts
      .option[String]("output", "Table | Tree", "o")
      .map(s => OutputFormat.withNameInvariant(s).getOrElse(OutputFormat.Tree))
      .orNone

    val smode = Opts.flag("unmasked", "Unmasked sensetive data in console output").orFalse

    (grep, output, smode).mapN((v, o, m) =>
      Action.Filter(v.getOrElse(SecretFilter.Empty),
                    o.getOrElse(OutputFormat.Tree),
        if m then SensitiveMode.Show else SensitiveMode.Masked
      )
    )
  }

  val remove: Opts[Action] = Opts.subcommand("rm", "Remove secret") {
    val name = Opts.argument[String]("name").mapValidated(SecretName.of)
    name.map(Action.Remove(_))
  }

  val command: Command[Action] =
    Command("alpasso", "header", true)(repos orElse add orElse remove orElse list orElse patch)

@main
def parse(): Unit = {
  val init = ArgParser
    .command
    .parse(Seq("repo",
               "init",
               "-p",
               ".",
               "--gpg-fingerprint",
               "5573E42BAA9D46C0F8D8C466CA6BEF44194FF928"
           ),
           sys.env
    )

  println(init)
  val add = ArgParser.command.parse(Seq("new", ""))

  val ls = ArgParser.command.parse(Seq("ls", "--grep", "proton"))
  println(ls)
}
