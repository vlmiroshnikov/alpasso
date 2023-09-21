package pass.core.model

opaque type SecretName <: String = String
object SecretName:
  def of(name: String): SecretName = name
