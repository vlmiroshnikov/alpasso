package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

import alpasso.common.{Converter, RawPackage}
import alpasso.core.model.*
import alpasso.service.fs.model.given
import alpasso.service.fs.model.{RawMetadata, RawSecretData}

import Console.*

case class SecretView(
    name: SecretName,
    payload: Option[String] = None,
    metadata: Option[SecretMetadata])

given Converter[RawPackage, SecretView] =
  rp => SecretView(rp.name, new String(rp.payload._1.byteArray).some, rp.payload._2.into().some)

object SecretView:

  given Show[SecretView] = Show.show { s =>
    val tags = s.metadata.fold("")(_.asMap.map((k, v) => s"$k=$v").mkString(","))
    s"${GREEN}${s.name}${RESET} $BLUE${s.payload.getOrElse("******")}$RESET $YELLOW$tags$RESET"
  }
