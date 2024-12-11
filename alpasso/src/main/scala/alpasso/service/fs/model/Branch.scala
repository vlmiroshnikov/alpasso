package alpasso.service.fs.model

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

enum Branch[+A]:
  case Empty(path: Path)
  case Solid(path: Path, data: A)

  def traverse[F[_], B](f: A => F[B])(implicit F: Applicative[F]): F[Branch[B]] =
    this match {
      case Solid(path, a) => F.map(f(a))(Solid.apply(path, _))
      case Empty(path) => F.pure(Empty.apply(path))
    }

object Branch:

  extension [A](b: Branch[A])

    def toEmpty: Branch[A] =
      b match
        case a @ Empty(_)   => a
        case Solid(path, _) => Empty(path)

    def fold[B](empty: => B, solid: A => B): B =
      b match
        case Branch.Empty(_)       => empty
        case Branch.Solid(_, data) => solid(data)

    def toOption: Option[A] =
      fold(none[A], identity(_).some)

  given [A: Show]: Show[Branch[A]] = Show.show:
    case Branch.Empty(path)       => path.getFileName.toString
    case Branch.Solid(path, data) => data.show

  given Functor[Branch] = new Functor[Branch]:
    override def map[A, B](fa: Branch[A])(f: A => B): Branch[B] =
      fa match
        case Empty(dir)        => Branch.Empty(dir)
        case Solid(path, data) => Branch.Solid(path, f(data))
