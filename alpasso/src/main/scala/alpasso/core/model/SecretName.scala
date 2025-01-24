package alpasso.core.model

import cats.*
import cats.data.{Validated, ValidatedNel}

import java.nio.file.{Path, Paths}

opaque type SecretName <: String = String

object SecretName:
  def of(name: String): ValidatedNel[String, SecretName] =
    if name.isEmpty then Validated.invalidNel("Must be not empty")
    else Validated.valid(Paths.get(name).normalize().toString)

  given Show[SecretName] = Show.fromToString
