package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

import alpasso.core.model.*

import Console.*

case class SecretView(
    name: SecretName,
    metadata: Option[MetadataView],
    payload: Option[String] = None)

object SecretView:

  given Show[SecretView] = Show.show(s =>
    s" ${GREEN}${s.name}${RESET} ${BLUE_B} ${s.payload.getOrElse("******")}${RESET} ${s.metadata.show}"
  )
