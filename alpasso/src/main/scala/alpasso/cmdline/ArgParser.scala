package alpasso.cmdline

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

import alpasso.cmdline.models.*
import alpasso.domain.*
import alpasso.infrastructure.cypher.{ CypherAlg, Recipient }

import com.monovore.decline.*

object ArgParser:

  given Argument[Recipient] = Argument.readString.map(Recipient.hex)

  val repos: Opts[Action] = Opts.subcommand("repo", "Repository operations") {

    val init = Opts.subcommand("init", "Init new repository") {
      val path      = Opts.option[Path]("path", "Repository path", "p")
      val gpg       = Opts.option[Recipient]("gpg-fingerprint", "GPG fingerprint").map(CypherAlg.Gpg(_)).orNone
      val masterKey = Opts.flag("master-key", "Use master key encryption").map(_ => CypherAlg.MasterKey).orNone

      (path, gpg, masterKey).mapN { (p, g, m) =>
        val cypher = (g, m) match
          case (Some(gpgAlg), None)    => gpgAlg
          case (None, Some(masterKey)) => masterKey
          case (Some(_), Some(_))      => CypherAlg.MasterKey // If both specified, prefer master-key
          case (None, None)            => CypherAlg.Gpg(Recipient.hex("")) // Default to GPG (will fail validation)
        RepoOp.Init(p, cypher)
      }
    }

    val list = Opts.subcommand("list", "List repository") {
      Opts.apply(RepoOp.List)
    }

    val switch = Opts.subcommand("switch", "Switch repository") {
      val path = Opts.argument[Int]("index")
      path.map(RepoOp.Switch.apply)
    }

    val log = Opts.subcommand("log", "Log repository") {
      Opts.apply(RepoOp.Log)
    }

    val remote = Opts.subcommand("remote", "Remote operations") {
      val setup = Opts.subcommand("setup", "Add origin remote repository") {
        val url  = Opts.argument[String]("url")
        val name = Opts.argument[String]("name").withDefault("origin")
        (name, url).mapN(RemoteOp.Setup.apply)
      }

      val sync = Opts.subcommand("sync", "Sync with remote repository") {
        Opts.apply(RemoteOp.Sync)
      }

      (setup orElse sync).map(RepoOp.RemoteOps.apply)
    }

    List(init, list, switch, log, remote).combineAll.map(Action.Repo.apply)
  }

  val add: Opts[Action] = Opts.subcommand("new", "Add new secret") {
    val name   = Opts.argument[String]("name").mapValidated(SecretName.of)
    val secret = Opts.argument[String]("secret").map(SecretPayload.fromString).orNone
    val tags   = Opts
      .option[String]("meta", "k1=v1,k2=v2")
      .mapValidated[SecretMetadata](SecretMetadata.fromRaw)
      .orNone

    (name, secret, tags).mapN(Action.New.apply)
  }

  val patch: Opts[Action] = Opts.subcommand("patch", "Update existing secret") {
    val name   = Opts.argument[String]("name").mapValidated(SecretName.of)
    val secret = Opts.argument[String]("secret").map(SecretPayload.fromString).orNone
    val tags   = Opts
      .option[String]("meta", "k1=v1,k2=v2")
      .mapValidated[SecretMetadata](SecretMetadata.fromRaw)
      .orNone

    (name, secret, tags).mapN(Action.Patch.apply)
  }

  val list: Opts[Action] = Opts.subcommand("ls", "List secrets") {
    val grep   = Opts.option[String]("grep", "Grep expression").map(SecretFilter.Grep.apply).orNone
    val output = Opts
      .option[String]("output", "Table | Tree", "o")
      .map(s => OutputFormat.withNameInvariant(s).getOrElse(OutputFormat.Tree))
      .orNone

    val smode = Opts.flag("unmasked", "Show sensitive data in console output").orFalse

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
