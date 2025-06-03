package alpasso.core.model

import java.nio.file.{ Path, Paths }
import java.util.Spliterators

import scala.jdk.CollectionConverters.*

import cats.*
import cats.data.{ Validated, ValidatedNel }

opaque type SecretName <: String = String

object SecretName:

  def of(name: String): ValidatedNel[String, SecretName] =
    if name.isEmpty then Validated.invalidNel("Must be not empty")
    else Validated.valid(Paths.get(name).normalize().toString)

  given Show[SecretName] = Show.show { s =>
    val last = Spliterators.iterator(Path.of(s).spliterator()).asScala.toList.last
    last.toString()
  }

enum SensetiveMode:
  case Show, Masked
