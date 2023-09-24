package alpasso.service.fs.model

import java.nio.file.Path

import alpasso.core.model.SecretName

enum StorageErr:
  case NotInitialized
  case DirAlreadyExists(path: Path, name: SecretName)
  case FileNotFound(path: Path, name: SecretName)
  case MetadataFileCorrupted(path: Path, name: SecretName)

type StorageResult[A] = Either[StorageErr, A]
