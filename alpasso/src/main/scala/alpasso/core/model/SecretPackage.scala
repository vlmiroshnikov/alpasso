package alpasso.core.model

import cats.*

case class SecretPackage[+T](name: SecretName, payload: T)

object SecretPackage:

  given Functor[SecretPackage]:

    def map[A, B](fa: SecretPackage[A])(f: A => B): SecretPackage[B] =
      SecretPackage(fa.name, f(fa.payload))
