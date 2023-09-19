package pass.cli

import java.nio.file.*
import java.nio.file.attribute.*
import scala.collection.*
import java.io.*
import cats.data.*
import cats.*
import cats.syntax.show.given

case class Node[T](data: T, siblings: Chain[Node[T]] = Chain.nil)

object Node:


  /*
  instance Applicative MyTree where
     pure x = MyNode x []
     (MyNode f treeFunctionList) <*> (MyNode x treeElementList) =
        MyNode (f x) ( (map (fmap f) treeElementList) ++ (map (<*> (MyNode x treeElementList)) treeFunctionList) )

   */
  given Applicative[Node] = new Applicative[Node]:
    override def pure[A](x: A): Node[A] = Node(x)
    override def ap[A, B](ff: Node[A => B])(fa: Node[A]): Node[B] =
      val f       = ff.data
      val funList = ff.siblings

      val x      = fa.data
      val elemList = fa.siblings
      Node(f(x),  elemList.map(enode => this.map(enode)(f)) ++ funList.map( fe => ap(fe)(Node(x,elemList))))

  given TraverseFilter[Node] = new TraverseFilter[Node]:
    override def traverse: Traverse[Node] = summon[Travedrse[Node]]
    override def traverseFilter[G[_], A, B](fa: Node[A])(f: A => G[Option[B]])(implicit G: Applicative[G]): G[Node[B]] =
      G.map(F.traverse(fa.data)(ga => traverseFilter(ga)(f)))(Nested[F, G, B])



  given Traverse[Node] = new Traverse[Node]:

    override def traverse[G[_]: Applicative, A, B](fa: Node[A])(f: A => G[B]): G[Node[B]] =
      Applicative[G].map2(f(fa.data), Traverse[Chain].traverse(fa.siblings)(v => traverse(v)(f)))(
        Node.apply)

    override def foldLeft[A, B](fa: Node[A], b: B)(f: (B, A) => B): B =
      f(fa.siblings.foldLeft(b)((agg, node) => f(agg, node.data)), fa.data)

    override def foldRight[A, B](fa: Node[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      f(fa.data, fa.siblings.foldRight(lb)((node, lagg) => f(node.data, lagg)))

def printRoot[T: Show](root: Node[T]): String =
  val lastPointer   = "└──"
  val middlePointer = "├──"

  def traverseNodes(
      sb: StringBuilder,
      padding: String,
      pointer: String,
      node: Node[T],
      hasRightSibling: Boolean): Unit =
    sb.append("\n")
    sb.append(padding)
    sb.append(pointer)
    sb.append(node.data.show)

    val paddingBuilder = new StringBuilder(padding)
    paddingBuilder.append(if hasRightSibling then "│  " else "   ")

    val paddingForBoth = paddingBuilder.toString()
    node.siblings.initLast match
      case Some((init, last)) =>
        init.toList.foreach { sib => traverseNodes(sb, paddingForBoth, middlePointer, sib, true) }
        traverseNodes(sb, paddingForBoth, lastPointer, last, false)
      case _ => ()

  val sb = StringBuilder()
  sb.append(root.data)

  root.siblings.initLast match
    case Some((init, last)) =>
      init.toList.foreach { sib => traverseNodes(sb, "", middlePointer, sib, true) }
      traverseNodes(sb, "", lastPointer, last, false)
    case None => ()
  sb.toString()

def walkFileTree(root: Path, exceptDir: Path => Boolean): Node[Path] =
  val stub = Node(Paths.get("."))

  val stack = mutable.Stack[Node[Path]](stub)
  val visitor: FileVisitor[Path] = new FileVisitor[Path]:
    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
      if exceptDir(dir) then FileVisitResult.SKIP_SUBTREE
      else
        val current = Node(dir)
        stack.push(current)
        FileVisitResult.CONTINUE

    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
      FileVisitResult.CONTINUE

    override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
      FileVisitResult.CONTINUE

    override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
      val current = stack.pop()
      val top     = stack.pop()
      val updated = top.copy(siblings = top.siblings.append(current))
      stack.push(updated)
      FileVisitResult.CONTINUE

  Files.walkFileTree(root, visitor)
  stack.head.siblings.headOption.getOrElse(stub)

@main
def main =
  val tree         = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  given Show[Path] = Show.show(_.getFileName.toString)
  println(printRoot(tree))
