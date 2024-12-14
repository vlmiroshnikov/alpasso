package alpasso.cmdline.view

import scala.Console.*

import cats.*
import cats.syntax.show.*

import alpasso.cmdline.*
import alpasso.common.Converter
import alpasso.core.model.SecretName.given
import alpasso.service.fs.{ProvisionErr, RepositoryErr}

case class ErrorView(error: String, suggest: Option[String] = None)

object ErrorView:

  given Show[ErrorView] = Show.show(s => s"${s.error} ${s.suggest.getOrElse("")}")

given Converter[Err, ErrorView] =
  case Err.RepositoryProvisionErr(ProvisionErr.AlreadyExists(path)) =>
    ErrorView(s"${RED}Repository at ${RESET}${BLUE}[${path.toString}]${RESET} ${RED}is already exists${RESET}")

  case Err.SecretRepoErr(RepositoryErr.AlreadyExists(name)) =>
    ErrorView(s"${RED}Secret ${RESET}${BLUE}[${name.show}]${RESET} ${RED}is already exists${RESET}")

  case ee => ErrorView(s"Undefined error: ${ee}")
