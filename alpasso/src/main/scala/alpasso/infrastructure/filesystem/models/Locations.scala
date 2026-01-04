package alpasso.infrastructure.filesystem.models

import java.nio.file.Path

import cats.syntax.all.*

import alpasso.domain.SecretName

opaque type PayloadPath <: Path = Path

object PayloadPath:
  def of(base: Path): PayloadPath = base.resolve("payload")

opaque type MetaPath <: Path = Path

object MetaPath:
  def of(base: Path): MetaPath = base.resolve("meta")

opaque type RepoRootDir <: Path = Path

object RepoRootDir:

  def fromPath(path: Path): Either[String, RepoRootDir] =
    if path.isAbsolute then path.normalize().asRight else "Required absolute path".asLeft

trait SecretPathEntries:
  def root: Path
  def meta: MetaPath
  def payload: PayloadPath

object SecretPathEntries:

  def from(root: RepoRootDir, name: SecretName): SecretPathEntries =
    val entry = root.resolve(name.asPath)
    new SecretPathEntries:
      override val root: Path           = entry
      override val meta: MetaPath       = MetaPath.of(entry)
      override val payload: PayloadPath = PayloadPath.of(entry)
