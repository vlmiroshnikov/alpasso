package alpasso.cmdline

import java.nio.file.Path

import cats.Show

import alpasso.domain.*
import alpasso.infrastructure.cypher.CypherAlg

object models:

  enum RemoteOp:
    case Setup(name: String, url: String)
    case Sync

  enum RepoOp:
    case Init(path: Path, cypher: CypherAlg)
    case List
    case Switch(index: Int)
    case Log
    case RemoteOps(ops: RemoteOp)
  // todo doctor

  enum Action:
    case Repo(ops: RepoOp)
    case New(name: SecretName, secret: Option[SecretPayload], meta: Option[SecretMetadata])
    case Patch(name: SecretName, payload: Option[SecretPayload], meta: Option[SecretMetadata])
    case Filter(where: SecretFilter, format: OutputFormat, sensitiveMode: SensitiveMode)
    case Remove(name: SecretName)

  enum OutputFormat:
    case Tree, Table

  object OutputFormat:

    def withNameInvariant(s: String): Option[OutputFormat] =
      OutputFormat.values.find(_.toString.toLowerCase == s)

    given Show[OutputFormat] = Show.show(_.toString.toLowerCase)
