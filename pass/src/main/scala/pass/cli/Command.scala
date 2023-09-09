package pass.cli

import cats.*
import cats.syntax.all.*
import cats.effect.*

enum Err :
  case AlreadyExists(name: Name)

type RejectionOr[A] = Either[Err, A] 

type Ctx

type Contexted[A] = Ctx ?=> RejectionOr[A]


trait Command[F[_]]:
  def create(name: Name): F[RejectionOr[Unit]]


