package alpasso.cmdline.view

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

import alpasso.service.cypher.CypherAlg

case class StorageView(repoDir: Path, cypherAlg: CypherAlg)

object StorageView:

  given Show[StorageView] =
    Show.show(s => s"Storage: ${s.repoDir.toString}  engine: ${s.cypherAlg.show}")
