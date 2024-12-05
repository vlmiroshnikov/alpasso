package alpasso.common

import alpasso.cmdline.Err

import logstage.LogIO

type Logger[F[_]]   = LogIO[F]
type RejectionOr[A] = Either[Err, A]
