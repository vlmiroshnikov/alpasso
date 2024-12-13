package alpasso.cli

import java.nio.file.Path

import cats.syntax.all.*

import alpasso.cmdline.view.{ OutputFormat, SecretFilter }
import alpasso.core.model.{ SecretMetadata, SecretName, SecretPayload }
import alpasso.service.cypher.CypherAlg

import com.monovore.decline.*

enum RepoOps:
  case Init(path: Option[Path], cypher: CypherAlg)
  case List
  case Switch(index: Int)

enum Action:
  case Repo(ops: RepoOps)
  case New(name: SecretName, secret: Option[SecretPayload], meta: Option[SecretMetadata])
  case Patch(name: SecretName, payload: Option[SecretPayload], meta: Option[SecretMetadata])
  case Filter(where: SecretFilter, format: OutputFormat)

object ArgParser:

  val repos: Opts[Action] = Opts.subcommand("repo", "Repositories ops") {

    val init = Opts.subcommand("init", "Init new repository") {
      val path = Opts.option[Path]("path", "Repository path", "p").orNone
      val gpg  = Opts.option[String]("gpg-fingerprint", "GPG fingerprint").map(CypherAlg.Gpg(_))

      (path, gpg).mapN(RepoOps.Init.apply)
    }

    val list = Opts.subcommand("list", "List repository") {
      Opts.apply(RepoOps.List)
    }

    val switch = Opts.subcommand("switch", "switch repository") {
      val path = Opts.argument[Int]("index")
      path.map(RepoOps.Switch.apply)
    }

    (init orElse list orElse switch).map(Action.Repo.apply)
  }

  val add: Opts[Action] = Opts.subcommand("new", "Add new secret") {
    val name   = Opts.argument("name").mapValidated(SecretName.of)
    val secret = Opts.argument("secret").map(SecretPayload.fromString).orNone
    val tags = Opts
      .option[String]("meta", "k1=v1,k2=v2")
      .mapValidated[SecretMetadata](SecretMetadata.fromRaw)
      .orNone

    (name, secret, tags).mapN(Action.New.apply)
  }

  val patch: Opts[Action] = Opts.subcommand("patch", "Update exists secret") {
    val name   = Opts.argument("name").mapValidated(SecretName.of)
    val secret = Opts.argument("secret").map(SecretPayload.fromString).orNone
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
    (grep, output).mapN((v, o) =>
      Action.Filter(v.getOrElse(SecretFilter.Empty), o.getOrElse(OutputFormat.Tree))
    )
  }

  val command: Command[Action] =
    Command("alpasso", "header", true)(repos orElse add orElse list orElse patch)

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
