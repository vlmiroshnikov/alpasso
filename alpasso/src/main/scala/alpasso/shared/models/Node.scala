package alpasso.shared.models

import scala.collection.*

import cats.*
import cats.data.*
import cats.syntax.all.*

case class Node[A](data: A, siblings: Chain[Node[A]] = Chain.nil)

object Node:

  given [A: Show]: Show[Node[A]] =
    Show.show(node => draw(node.traverse(n => Id(n.show))).mkString("\n"))

  given Traverse[Node] = new Traverse[Node]:
    override def traverse[G[_]: Applicative, A, B](fa: Node[A])(f: A => G[B]): G[Node[B]] =
      Applicative[G].map2(f(fa.data), Traverse[Chain].traverse(fa.siblings)(v => traverse(v)(f)))(
        Node.apply
      )

    override def foldLeft[A, B](fa: Node[A], b: B)(f: (B, A) => B): B =
      f(fa.siblings.foldLeft(b)((agg, node) => foldLeft(node, agg)(f)), fa.data)

    override def foldRight[A, B](fa: Node[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      f(fa.data, fa.siblings.foldRight(lb)((node, lagg) => foldRight(node, lagg)(f)))

def draw(root: Node[String]): List[String] =
  def drawSubTrees(s: List[Node[String]]): List[String] =
    s match
      case Nil      => Nil
      case t :: Nil => shift("╰─ ", "   ", draw(t))
      case t :: ts  => shift("├─ ", "│  ", draw(t)) ++ drawSubTrees(ts)

  def shift(first: String, other: String, s: List[String]): List[String] =
    s.zip(first #:: LazyList.continually(other)).map((a, b) => b.concat(a))

  root.data :: drawSubTrees(root.siblings.toList)
