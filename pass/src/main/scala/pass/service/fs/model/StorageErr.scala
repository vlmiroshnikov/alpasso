package pass.service.fs.model

import pass.core.model.SecretName
import java.nio.file.Path

enum StorageErr:
  case NotInitialized
  case DirAlreadyExists(path: Path, name: SecretName)
  case FileNotFound(path: Path, name: SecretName)

type StorageResult[A] = Either[StorageErr, A]
