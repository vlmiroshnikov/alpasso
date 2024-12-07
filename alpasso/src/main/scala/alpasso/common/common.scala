package alpasso.common

import alpasso.cmdline.Err

import logstage.LogIO

type Logger[F[_]] = LogIO[F]
type Result[A]    = Either[Err, A]
