package alpasso.domain

import cats.*

case class Secret[+T](name: SecretName, payload: T)

object Secret:

  given Functor[Secret]:

    def map[A, B](fa: Secret[A])(f: A => B): Secret[B] =
      Secret(fa.name, f(fa.payload))
