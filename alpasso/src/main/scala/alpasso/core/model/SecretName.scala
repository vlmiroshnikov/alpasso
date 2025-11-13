package alpasso.core.model

import java.nio.file.{ Path, Paths }
import java.util.Spliterators

import scala.jdk.CollectionConverters.*

import cats.*
import cats.data.{ Validated, ValidatedNel }

opaque type SecretName = Path

object SecretName:

  extension (s: SecretName)

    def shortName: String =
      Spliterators
        .iterator(s.spliterator())
        .asScala
        .toList
        .lastOption
        .map(_.toString())
        .getOrElse("")

    def asPath: Path = s

  def of(name: String): ValidatedNel[String, SecretName] =
    if name.isEmpty then Validated.invalidNel("Must be not empty")
    else Validated.valid(Paths.get(name).normalize())

  given Show[SecretName] = Show.show(_.shortName)

enum SensitiveMode:
  case Show, Masked
