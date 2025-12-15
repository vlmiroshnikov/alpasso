package alpasso.shared.models

import alpasso.domain.{ Secret, SecretMetadata, SecretPayload }
import alpasso.shared.errors.*

type Result[A] = Either[Err, A]

type Package = Secret[(SecretPayload, SecretMetadata)]

trait Converter[-From, +To] extends (From => To):
  def apply(x: From): To

  extension (x: From) def into(): To = this(x)
