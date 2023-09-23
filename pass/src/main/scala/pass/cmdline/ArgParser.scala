package pass.cmdline

import java.net.URL
import java.nio.file.Path

import cats.syntax.all.*
import pass.core.model.*

import scopt.OParser

enum SecretFilter:
  case Predicate(pattern: String)
  case All
  case Empty

enum OutputFormat:
  case Tree, Table

enum Action:
  case InitWithPath(repoDir: Path)
  case InitFromRepository(url: URL)

  case CreateSecret(
      name: Option[String],
      secret: Option[SecretPayload],
      meta: Map[String, String] = Map.empty)
  case FindSecrets(filter: Option[SecretFilter], outputFormat: OutputFormat = OutputFormat.Tree)
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

  private val init = cmd("init")
    .children(
      opt[Path]("path")
        .withFallback(() => repoDirDefault)
        .action((path, c) => Action.InitWithPath(path)),
      checkConfig(c => success)
    )

  private val find = cmd("find")
    .children(
      opt[Unit]("all")
        .action((_, c) => Action.FindSecrets(SecretFilter.All.some)),
      opt[String]("predicate")
        .optional()
        .action((str, c) => Action.FindSecrets(SecretFilter.Predicate(str).some))
    )

  def parser: OParser[Unit, Action] =
    OParser.sequence(
      programName("pass"),
      // help("help"),
      init,
      create,
      find
    )
end ArgParser
