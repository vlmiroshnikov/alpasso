package pass.core.model

import cats.*

import java.nio.file.{Path, Paths}

opaque type SecretName <: String = String
object SecretName:
  def of(name: String): SecretName =
    Paths.get(name).normalize().toString

  given Show[SecretName] = Show.show(identity)
