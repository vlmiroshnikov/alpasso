package alpasso.service.fs.model

import java.nio.file.Path

import cats.*
import cats.syntax.all.*

enum Branch[+A]:
  case Node(path: Path)
  case Leaf(path: Path, data: A)

  def traverse[F[_]: Applicative as F, B](f: A => F[B]): F[Branch[B]] =
    this match
      case Leaf(path, a) => F.map(f(a))(Leaf.apply(path, _))
      case Node(path)    => F.pure(Node.apply(path))

object Branch:

  extension [A](b: Branch[A])

    def toEmpty: Branch[A] =
      b match
        case a @ Node(_)   => a
        case Leaf(path, _) => Node(path)

    def fold[B](empty: => B, solid: A => B): B =
      b match
        case Branch.Node(_)       => empty
        case Branch.Leaf(_, data) => solid(data)

    def toOption: Option[A] =
      fold(none[A], identity(_).some)

  given [A: Show]: Show[Branch[A]] = Show.show:
    case Branch.Node(path)       => path.getFileName.toString
    case Branch.Leaf(path, data) => data.show

  given Functor[Branch] with

    override def map[A, B](fa: Branch[A])(f: A => B): Branch[B] =
      fa match
        case Node(dir)        => Branch.Node(dir)
        case Leaf(path, data) => Branch.Leaf(path, f(data))
