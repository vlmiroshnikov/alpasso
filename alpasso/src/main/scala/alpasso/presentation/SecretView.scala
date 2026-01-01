package alpasso.presentation

import cats.*
import cats.syntax.all.*

import alpasso.domain.{ SecretName, SensitiveMode }
import alpasso.shared.models.{ Converter, Package }

import Console.*
import SimpleMetadataView.given

case class SecretView(
    name: SecretName,
    payload: Option[String] = None,
    metadata: Option[SimpleMetadataView])

given Converter[Package, SecretView] =
  rp => SecretView(rp.name, new String(rp.payload._1.byteArray).some, rp.payload._2.into().some)

object SecretView:

  given (
      using
      mode: SensitiveMode): Show[SecretView] = Show.show { s =>
    val secret = mode match
      case SensitiveMode.Show   => s.payload.getOrElse("")
      case SensitiveMode.Masked => s.payload.fold("")(_ => "*******")

    val meta = s.metadata.fold("")(_.show)
    s"${GREEN}${s.name.show}${RESET} $BLUE${secret}$RESET $meta"
  }
