package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

import alpasso.common.{ Converter, RawPackage }
import alpasso.core.model.*
import alpasso.service.fs.model.given
import alpasso.service.fs.model.{ RawMetadata, RawSecretData }

import Console.*

case class SecretView(
    name: SecretName,
    payload: Option[String] = None,
    metadata: Option[SecretMetadata])

given Converter[RawPackage, SecretView] =
  rp => SecretView(rp.name, new String(rp.payload._1.byteArray).some, rp.payload._2.into().some)

object SecretView:

  given (
      using
      mode: SensitiveMode): Show[SecretView] = Show.show { s =>

    val secret = mode match
      case SensitiveMode.Show   => s.payload.getOrElse("")
      case SensitiveMode.Masked => "*******"

    val tags = s.metadata.fold("")(_.asMap.map((k, v) => s"$k=$v").mkString(","))
    s"${GREEN}${s.name.show}${RESET} $BLUE${secret}$RESET $YELLOW$tags$RESET"
  }
