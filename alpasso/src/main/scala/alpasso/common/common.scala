package alpasso.common

import alpasso.cmdline.Err
import alpasso.core.model.SecretPackage
import alpasso.service.fs.model.{ RawMetadata, RawSecretData }

type Result[A] = Either[Err, A]

type RawPackage = SecretPackage[(RawSecretData, RawMetadata)]

trait Converter[-From, +To] extends (From => To):
  def apply(x: From): To

  extension (x: From) def into(): To = this(x)
