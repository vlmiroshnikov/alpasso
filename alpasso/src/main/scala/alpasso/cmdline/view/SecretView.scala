package alpasso.cmdline.view

import cats.*
import cats.syntax.all.*

import alpasso.common.{ Converter, Package }
import alpasso.core.model.*

import Console.*
import SimpleMetadataView.given

case class SecretView(
    name: SecretName,
    payload: Option[String] = None,
    metadata: Option[SimpleMetadataView])

given Converter[Package, SecretView] =
  rp => SecretView(rp.name, new String(rp.payload._1.rawData).some, rp.payload._2.into().some)

object SecretView:

  given (
      using
      mode: SensitiveMode): Show[SecretView] = Show.show { s =>

    val secret = mode match
      case SensitiveMode.Show   => s.payload.getOrElse("")
      case SensitiveMode.Masked => "*******"

    val meta = s.metadata.fold("")(_.show)
    s"${GREEN}${s.name.show}${RESET} $BLUE${secret}$RESET $meta"
  }
