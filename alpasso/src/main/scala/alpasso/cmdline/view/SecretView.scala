package alpasso.cmdline.view

import cats.*

import alpasso.core.model.*

import Console.*

case class SecretView(
    name: SecretName,
    payload: Option[String] = None,
    metadata: Option[SecretMetadata])

object SecretView:

  given Show[SecretView] = Show.show { s =>
    val tags = s.metadata.fold("")(_.asMap.map((k, v) => s"$k=$v").mkString(","))
    s"${GREEN}${s.name}${RESET} $BLUE${s.payload.getOrElse("******")}$RESET $YELLOW$tags$RESET"
  }
