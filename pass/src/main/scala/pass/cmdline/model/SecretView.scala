package pass.cmdline.model

import cats.*
import cats.syntax.all.*
import pass.core.model.*

import java.nio.file.Path

case class MetadataView()

object MetadataView:
  given Show[MetadataView] = Show.show(s => s"meta: fix it")

case class SecretView(name: SecretName, metadata: MetadataView)

object SecretView:
  given Show[SecretView] = Show.show(s => s"[${s.name}]* ${s.metadata.toString}")

case class StorageView(repoDir: Path)

object StorageView:
  given Show[StorageView] = Show.show(s => s"storage: ${s.repoDir.toString}")
