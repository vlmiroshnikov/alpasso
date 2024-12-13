package alpasso.common

import alpasso.cmdline.Err

import logstage.LogIO

type Logger[F[_]] = LogIO[F]
type Result[A]    = Either[Err, A]

trait Converter[-T, +U] extends (T => U):
  def apply(x: T): U

  extension (x: T) def into(): U = this(x)
