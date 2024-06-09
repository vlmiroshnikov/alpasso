package alpasso.core.model

import cats.*

case class SecretPacket[+T](name: SecretName, payload: T)

object SecretPacket:
  given Functor[SecretPacket] = new Functor[SecretPacket]:
    def map[A, B](fa: SecretPacket[A])(f: A => B): SecretPacket[B] =
      SecretPacket(fa.name, f(fa.payload))
      
