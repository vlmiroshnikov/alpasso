package alpasso.common.syntax

import cats.Functor
import cats.data.EitherT

import glass.Upcast

extension [F[_]: Functor, A, B](fa: F[Either[A, B]])
  def toEitherT: EitherT[F, A, B] = EitherT(fa)

  def liftE[E](
      using
      up: Upcast[E, A]): EitherT[F, E, B] =
    EitherT(fa).leftMap(a => up.upcast(a))
