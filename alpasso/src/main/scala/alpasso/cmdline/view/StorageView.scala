package alpasso.cmdline.view

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

case class StorageView(repoDir: Path)

object StorageView:
  given Show[StorageView] = Show.show(s => s"storage: ${s.repoDir.toString}")
