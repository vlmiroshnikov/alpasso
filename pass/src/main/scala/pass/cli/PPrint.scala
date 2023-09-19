package pass.cli

import java.nio.file.*
import java.nio.file.attribute.*
import scala.collection.*
import java.io.*
import cats.data.*
import cats.*
import cats.syntax.show.given
enum Tree[+T]:
  case Node[A](data: A, siblings: Chain[Tree[A]] = Chain.nil) extends Tree[A]
  case Empty extends Tree[Nothing]


object Tree:
  extension[A] (t: Tree[A])
    def leafs: Chain[Tree[A]] = t match
      case Tree.Node(data, sub) => sub
      case Tree.Empty => Chain.empty

  /*
  instance Applicative MyTree where
     pure x = MyNode x []
     (MyNode f treeFunctionList) <*> (MyNode x treeElementList) =
        MyNode (f x) ( (map (fmap f) treeElementList) ++ (map (<*> (MyNode x treeElementList)) treeFunctionList) )

   */
  given Applicative[Tree] = new Applicative[Tree]:
    override def pure[A](x: A): Tree[A] = Node(x)
    override def ap[A, B](ff: Tree[A => B])(fa: Tree[A]): Tree[B] =
      (ff, fa) match
        case (Node(f, funList), Node(x, elemList)) =>
          Node(f(x),  elemList.map(enode => this.map(enode)(f)) ++ funList.map( fe => ap(fe)(Node(x,elemList))))
        case (Node(f, funList), Node(x, elemList)) =>  ???

  given TraverseFilter[Tree] = new TraverseFilter[Tree]:
    private val F = summon[Traverse[Tree]]
    override def traverse: Traverse[Tree] = F

    /*
    def traverseFilter[H[_], A, B](
      fga: Nested[F, G, A]
    )(f: A => H[Option[B]])(implicit H: Applicative[H]): H[Nested[F, G, B]] =
      H.map(F.traverse[H, G[A], G[B]](fga.value)(ga => G.traverseFilter(ga)(f)))(Nested[F, G, B])
     */

    /*
    override def traverseFilter[G[_], A, B](
      fa: Iterable[A]
    )(f: A => G[Option[B]])(implicit G: Applicative[G]): G[Iterable[B]] =
      if (fa.isEmpty) G.pure(Iterable.empty)
      else G.map(Chain.traverseFilterViaChain(toImIndexedSeq(fa))(f))(_.toVector)
     */
    override def traverseFilter[G[_], A, B](tree: Tree[A])(f: A => G[Option[B]])(implicit G: Applicative[G]): G[Tree[B]] =
      tree match
        case Tree.Empty  => G.pure(Tree.Empty)
        case Tree.Node(data, siblings) =>
//          val data = node.data
//          val siblings = node.siblings

          val dataG: G[Option[B]] =  f(data)
          val sibG  = Traverse[Chain].traverse(siblings)(v => traverseFilter(v)(f))

          G.map2(dataG, sibG) {
            case (Some(data), sib) => Node(data, sib)
            case (None, _)         => Tree.Empty
          }

  given Traverse[Tree] = new Traverse[Tree]:

    override def traverse[G[_] : Applicative, A, B](fa: Tree[A])(f: A => G[B]): G[Tree[B]] =
      fa match
        case Tree.Node(data, siblings) =>
          Applicative[G].map2(f(data), Traverse[Chain].traverse(siblings)(v => traverse(v)(f)))(Node.apply)
        case Tree.Empty => Applicative[G].pure(Tree.Empty)

    override def foldLeft[A, B](fa: Tree[A], b: B)(f: (B, A) => B): B = ???
     // f(fa.siblings.foldLeft(b)((agg, node) => f(agg, node.data)), fa.data)

    override def foldRight[A, B](fa: Tree[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      ???
      //f(fa.data, fa.siblings.foldRight(lb)((node, lagg) => f(node.data, lagg)))

def printRoot[T: Show](root: Tree[T]): String = ???
//  val lastPointer   = "└──"
//  val middlePointer = "├──"
//
//  def traverseNodes(
//      sb: StringBuilder,
//      padding: String,
//      pointer: String,
//      node: Tree[T],
//      hasRightSibling: Boolean): Unit =
//    sb.append("\n")
//    sb.append(padding)
//    sb.append(pointer)
//    sb.append(node.data.show)
//
//    val paddingBuilder = new StringBuilder(padding)
//    paddingBuilder.append(if hasRightSibling then "│  " else "   ")
//
//    val paddingForBoth = paddingBuilder.toString()
//    node.siblings.initLast match
//      case Some((init, last)) =>
//        init.toList.foreach { sib => traverseNodes(sb, paddingForBoth, middlePointer, sib, true) }
//        traverseNodes(sb, paddingForBoth, lastPointer, last, false)
//      case _ => ()
//
//  val sb = StringBuilder()
//  sb.append(root.data)
//
//  root.siblings.initLast match
//    case Some((init, last)) =>
//      init.toList.foreach { sib => traverseNodes(sb, "", middlePointer, sib, true) }
//      traverseNodes(sb, "", lastPointer, last, false)
//    case None => ()
//  sb.toString()

def walkFileTree(root: Path, exceptDir: Path => Boolean): Tree[Path] =
  val stub = Tree.Node(Paths.get("."))

  val stack = mutable.Stack[Tree[Path]](stub)
  val visitor: FileVisitor[Path] = new FileVisitor[Path]:
    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
      if exceptDir(dir) then FileVisitResult.SKIP_SUBTREE
      else
        val current = Tree.Node(dir)
        stack.push(current)
        FileVisitResult.CONTINUE

    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
      FileVisitResult.CONTINUE

    override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
      FileVisitResult.CONTINUE

    override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
      val current = stack.pop()
      val updated = stack.pop() match
        case top: Tree.Node[Path] =>
          top.copy(siblings = top.siblings.append(current))
        case a@Tree.Empty => a

      stack.push(updated)
      FileVisitResult.CONTINUE

  Files.walkFileTree(root, visitor)
  val siblings = stack.head.leafs
  siblings.headOption.getOrElse(stub)

@main
def main =
  val tree         = walkFileTree(Paths.get("", ".tmps"), _.endsWith(".git"))
  given Show[Path] = Show.show(_.getFileName.toString)
  println(printRoot(tree))
