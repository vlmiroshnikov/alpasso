package alpasso.shared

import java.nio.file.Path

import alpasso.infrastructure.cypher.*
import alpasso.infrastructure.filesystem.*
import alpasso.infrastructure.filesystem.RepoMetaErr.{ InvalidFormat, NotInitialized }
import alpasso.infrastructure.filesystem.RepositoryErr.fromGitError
import alpasso.infrastructure.git.GitError
import alpasso.shared.models.SemVer
import alpasso.shared.syntax.*

import glass.*

object errors:

  enum Err:
    case SecretRepoErr(err: RepositoryErr)
    case StorageNotInitialized(path: Path)
    case InconsistentStorage(reason: String)
    case StorageCorrupted(path: Path)

    case InternalErr
    case CommandSyntaxError(help: String)
    case UseSwitchCommand
    case VersionMismatch(version: SemVer)
    case RepositoryProvisionErr(err: ProvisionErr)

  object Err:
    given Upcast[Err, RepositoryErr] = Err.SecretRepoErr(_)

    given Upcast[Err, RepoMetaErr] =
      case NotInitialized(path) => Err.StorageNotInitialized(path)
      case InvalidFormat(path)  => Err.StorageCorrupted(path)

    given Upcast[Err, ProvisionErr] = e => Err.RepositoryProvisionErr(e)
    given Upcast[Err, CypherError]  = e => Err.SecretRepoErr(e.upcast)

    given Upcast[Err, GitError] =
      ge => summon[Upcast[Err, RepositoryErr]].upcast(fromGitError(ge)) // todo fix it
  end Err
