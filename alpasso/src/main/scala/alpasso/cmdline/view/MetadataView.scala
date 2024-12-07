package alpasso.cmdline.view

import cats.Show
import cats.syntax.all.*

import alpasso.core.model.SecretMetadata

case class MetadataView(metadata: SecretMetadata)

object MetadataView:
  given Show[MetadataView] = Show.show(s => s.metadata.show)
