package alpasso.cmdline.view

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

import alpasso.core.model.*

case class SecretView(name: SecretName, metadata: MetadataView)

object SecretView:
  given Show[SecretView] = Show.show(s => s"[${s.name}]* ${s.metadata.show}")
