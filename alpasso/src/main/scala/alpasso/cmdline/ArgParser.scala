package alpasso.cmdline

import java.net.URL
import java.nio.file.Path

import cats.Show
import cats.syntax.all.*

import alpasso.core.model.*

import scopt.*
import scopt.OParser

enum SecretFilter:
  case Predicate(pattern: String)
  case All
  case Empty

enum OutputFormat:
  case Tree, Table

object OutputFormat:
  given Show[OutputFormat] = Show.show(_.toString.toLowerCase)

enum Action:
  case InitWithPath(repoDir: Path)
  case InitFromRepository(url: URL)

  case CreateSecret(
      name: Option[String] = None,
      secret: Option[SecretPayload] = None,
      meta: Map[String, String] = Map.empty)

  case UpdateSecret(
      name: Option[String] = None,
      secret: Option[SecretPayload] = None,
      meta: Option[Map[String, String]] = None)

  case FindSecrets(
      filter: Option[SecretFilter] = None,
      outputFormat: OutputFormat = OutputFormat.Tree)
  case Empty

case class ArgParser(repoDirDefault: Path):
  private val builder = OParser.builder[Action]
  import builder.*

  private val create = cmd("create")
    .children(
      arg[String]("name")
        .required()
        .text("secret name")
        .action {
          case (name, a: Action.CreateSecret) => a.copy(name = name.some)
          case (name, _)                      => Action.CreateSecret(name.some, none)
        },
      arg[String]("secret")
        .required()
        .text("secret phrase")
        .action {
          case (s, a: Action.CreateSecret) => a.copy(secret = SecretPayload.fromString(s).some)
          case (s, _) => Action.CreateSecret(name = none, secret = SecretPayload.fromString(s).some)
        },
      opt[Map[String, String]]("tags")
        .valueName("k1=v1,k2=v2...")
        .text("metadata")
        .action {
          case (tags, a: Action.CreateSecret) => a.copy(meta = tags)
          case (_, a)                         => a
        },
      checkConfig {
        case a: Action.CreateSecret if a.name.nonEmpty && a.secret.nonEmpty => success
        case a: Action.CreateSecret                                         => failure(s"fail $a")
        case other                                                          => success
      }
    )

  private val update = cmd("update")
    .children(
      arg[String]("name")
        .required()
        .text("secret name")
        .action {
          case (name, a: Action.UpdateSecret) => a.copy(name = name.some)
          case (name, Action.Empty)           => Action.UpdateSecret(name = name.some, none, none)
          case (name, a)                      => a
        },
      arg[String]("secret")
        .optional()
        .text("secret phrase")
        .action {
          case (s, a: Action.UpdateSecret) => a.copy(secret = SecretPayload.fromString(s).some)
          case (s, Action.Empty) =>
            Action.UpdateSecret(secret = SecretPayload.fromString(s).some, name = none, meta = none)
          case (name, a)                   => a
        },
      opt[Map[String, String]]("tags")
        .valueName("k1=v1,k2=v2...")
        .text("metadata")
        .action {
          case (tags, a: Action.UpdateSecret) => a.copy(meta = tags.some)
          case (_, a)                         => a
        },
      checkConfig {
        case a: Action.UpdateSecret if a.name.nonEmpty => success
        case a: Action.UpdateSecret                    => failure(s"fail $a")
        case other                                     => success
      }
    )

  private val init = cmd("init")
    .children(
      opt[Path]("path")
        .withFallback(() => repoDirDefault)
        .action((path, c) => Action.InitWithPath(path)),
      checkConfig(c => success)
    )

  given Read[OutputFormat] = Read
    .stringRead
    .map:
      case "table" => OutputFormat.Table
      case "tree"  => OutputFormat.Tree
      case s       => throw new IllegalArgumentException("'" + s + "'.")

  private val find = cmd("find")
    .children(
      opt[Unit]("all")
        .action((_, c) => Action.FindSecrets(SecretFilter.All.some)),
      opt[String]("predicate")
        .optional()
        .action((str, c) => Action.FindSecrets(SecretFilter.Predicate(str).some)),
      opt[OutputFormat]("format")
        .valueName(OutputFormat.values.map(_.show).mkString("[", "|", "]"))
        .optional()
        .action {
          case (fmt, c: Action.FindSecrets) => c.copy(outputFormat = fmt)
          case (fmt, c)                     => c
        }
    )

  def parser: OParser[Unit, Action] =
    OParser.sequence(
      programName("alpasso"),
      help("help"),
      init,
      create,
      update,
      find
    )
end ArgParser
