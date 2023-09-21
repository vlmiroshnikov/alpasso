package pass.cmdline

import cats.syntax.all.*
import pass.core.model.*
import scopt.OParser

import java.net.URL
import java.nio.file.Path

enum SecretFilter:
  case Empty

enum Action:
  case InitWithPath(repoDir: Path)
  case InitFromRepository(url: URL)
  case CreateSecret(name: Option[SecretName], secret: Option[SecretPayload])
  case ListSecrets(filter: SecretFilter)
  case Empty

case class ArgParser(repoDirDefault: Path):
  private val builder = OParser.builder[Action]
  import builder.*

  private val create = cmd("create")
    .text("create new secret.")
    .children(
      arg[String]("name")
        .required()
        .action {
          case (name, a: Action.CreateSecret) => a.copy(name = SecretName.of(name).some)
          case (name, _)                      => Action.CreateSecret(SecretName.of(name).some, none)
        },
      arg[String]("secret")
        .required()
        .action {
          case (s, a: Action.CreateSecret) => a.copy(secret = SecretPayload.fromString(s).some)
          case (s, _) => Action.CreateSecret(name = none, secret = SecretPayload.fromString(s).some)
        },
      checkConfig {
        case a: Action.CreateSecret if a.name.nonEmpty && a.secret.nonEmpty => success
        case a: Action.CreateSecret                                         => failure(s"fail $a")
        case other                                                          => success
      }
    )

  private val init = cmd("init")
    .text("init repository")
    .children(
      opt[Path]("path")
        .withFallback(() => repoDirDefault)
        .action((path, c) => Action.InitWithPath(path)),
      checkConfig(c => success)
    )

  private val list = cmd("list")
    .text("list")
    .abbr("ls")
    .children(
      arg[String]("string")
        .action((str, c) => Action.ListSecrets(SecretFilter.Empty))
    )

  def parser: OParser[Unit, Action] =
    OParser.sequence(
      programName("pass"),
      head("pass", "0.0.1"),
      init,
      create,
      list
    )
end ArgParser
