package alpasso.shared

import cats.Functor
import cats.data.EitherT

import glass.Upcast

object syntax:

  extension [F[_]: Functor, A, B](fa: F[Either[A, B]])
    def toEitherT: EitherT[F, A, B] = EitherT(fa)

    def liftE[E](
        using
        up: Upcast[E, A]): EitherT[F, E, B] =
      EitherT(fa).leftMap(a => up.upcast(a))

  extension [A](a: A)

    def upcast[B](
        using
        up: Upcast[B, A]): B = up.upcast(a)

end syntax
