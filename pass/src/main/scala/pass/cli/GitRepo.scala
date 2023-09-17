package pass.cli

import scala.util.*
import cats.*
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.effect.*
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.io.File
import java.nio.file.*

enum GitError:
  case RepositoryNotFound(path: Path)
  case UnexpectedError

trait GitRepo[F[_]]:
  def info: F[Path]
  def addFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]]

object GitRepo:

  def openExists[F[_]: Sync](repoDir: Path): Resource[F, Either[GitError, GitRepo[F]]] =
    val location = repoDir.resolve(".git")

    def adaptErr: Throwable => GitError =
      case e: RepositoryNotFoundException => GitError.RepositoryNotFound(location)
      case _                              => GitError.UnexpectedError

    val repoF = Sync[F].blocking:
      val builder = new FileRepositoryBuilder()
      builder
        .setGitDir(location.toFile)
        .readEnvironment() // scan environment GIT_* variables
        .findGitDir() // scan up the file system tree
        .setMustExist(true)
        .build()
    Resource
      .fromAutoCloseable(repoF)
      .redeem(adaptErr(_).asLeft, Impl(_).asRight)

  def create[F[_]: Sync](repoDir: Path): Resource[F, GitRepo[F]] = Resource
    .fromAutoCloseable(Sync[F].blocking {
      val home       = Files.createDirectory(repoDir)
      val repository = FileRepositoryBuilder.create(home.resolve(".git").toFile)
      repository.create()
      println(repository.getDirectory)
      repository
    })
    .map(Impl(_))

  class Impl[F[_]](
      using
      F: Sync[F]
    )(repository: Repository)
      extends GitRepo[F]:
    import F.blocking

    given Order[Path] = Order.fromComparable[Path]

    override def info: F[Path] = blocking(repository.getDirectory.toPath)

    override def addFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]] =
      blocking:
        val git = new Git(repository)
        val cmd = git.add()

        val parents = files.map(_.getParent).distinct
        parents
          .concatNel(files)
          .traverse(file => Try(cmd.addFilepattern(file.toString)).toEither)

        cmd.call()

        git
          .commit()
          .setMessage(message)
          .call()
          .asRight
