package alpasso.cli

import java.nio.file.Path

import cats.syntax.all.*

import alpasso.cmdline.view.{OutputFormat, SecretFilter}
import alpasso.core.model.{ SecretMetadata, SecretName, SecretPayload }

import com.monovore.decline.*

enum Action:
  case Init(path: Option[Path], cypher: Cypher)
  case New(name: SecretName, secret: Option[SecretPayload], meta: Option[SecretMetadata])
  case Filter(where: SecretFilter, format: OutputFormat)

enum Cypher:
  case Gpg(fingerprint: String)

object ArgParser:

  val init: Opts[Action] = Opts.subcommand("init", "Initialize new repository") {
    val path = Opts.option[Path]("path", "Repository path", "p").orNone
    val gpg  = Opts.option[String]("gpg-fingerprint", "GPG fingerprint").map(Cypher.Gpg(_))

    (path, gpg).mapN(Action.Init.apply)
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

  val command: Command[Action] = Command("alpasso", "header", true)(init orElse add orElse list)

@main
def parse(): Unit = {
  val init = ArgParser
    .command
    .parse(Seq("init", "-p", ".", "--gpg-fingerprint", "5573E42BAA9D46C0F8D8C466CA6BEF44194FF928"),
           sys.env
    )

  val add = ArgParser.command.parse(Seq("new", ""))

  val ls = ArgParser.command.parse(Seq("ls", "--grep", "proton"))
  println(ls)
}
