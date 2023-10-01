package alpasso.cmdline.view

import cats.Show
import cats.syntax.all.*

import alpasso.service.fs.model.Metadata

case class MetadataView(metadata: Metadata)

object MetadataView:
  given Show[MetadataView] = Show.show(s => s.metadata.show)
