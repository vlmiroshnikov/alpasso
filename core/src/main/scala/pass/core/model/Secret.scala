package pass.core.model

import cats.Functor

case class Secret[+T](name: SecretName, payload: T)

object Secret:
  given Functor[Secret] = new Functor[Secret]:
    def map[A, B](fa: Secret[A])(f: A => B): Secret[B] =
      Secret(fa.name, f(fa.payload))

