package alpasso.cmdline.model

import java.nio.file.Path

import cats.*
import cats.syntax.all.*
import alpasso.core.model.*
import alpasso.service.fs.model.Metadata

case class MetadataView(metadata: Metadata)

object MetadataView:
  given Show[MetadataView] = Show.show(s => s.metadata.show)

case class SecretView(name: SecretName, metadata: MetadataView)

object SecretView:
  given Show[SecretView] = Show.show(s => s"[${s.name}]* ${s.metadata.show}")

case class StorageView(repoDir: Path)

object StorageView:
  given Show[StorageView] = Show.show(s => s"storage: ${s.repoDir.toString}")

case class TableRowView[R](id: Int, data: R)

case class TableView[R](rows: List[TableRowView[R]])

object TableView:

  given [R: Show]: Show[TableView[R]] = Show.show { t =>
    val rw = t.rows.map(r => f"|${r.id}%2d | ${r.data.show}%50s |")
    (rw :+ "|" + "-".repeat(56) + "|").mkString("\n")
  }
