package pass.service.git

import java.nio.file.*

import scala.util.*
import scala.util.control.NoStackTrace

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.{ Ref, Repository }
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

enum GitError extends Throwable with NoStackTrace:
  case RepositoryNotFound(path: Path)
  case RepositoryIsDirty
  case UnexpectedError

trait GitRepo[F[_]]:
  def info: F[Path]
  def verify(): F[Either[GitError, Unit]]
  def addFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]]

object GitRepo:

  def openExists[F[_]](
      repoDir: Path
    )(using
      F: Sync[F]): Resource[F, GitRepo[F]] =
    import F.blocking

    val location = repoDir.resolve(".git")

    def adaptErr: Throwable => GitError =
      case e: RepositoryNotFoundException => GitError.RepositoryNotFound(location)
      case _                              => GitError.UnexpectedError

    val repoF = blocking:
      val builder = new FileRepositoryBuilder()
      builder
        .setGitDir(location.toFile)
        .readEnvironment() // scan environment GIT_* variables
        .findGitDir() // scan up the file system tree
        .setMustExist(true)
        .build()

    Resource
      .fromAutoCloseable(repoF)
      .map(Impl(_))

  def create[F[_]: Sync](repoDir: Path): Resource[F, GitRepo[F]] = Resource
    .fromAutoCloseable(Sync[F].blocking {
      val home       = Files.createDirectory(repoDir)
      val repository = FileRepositoryBuilder.create(home.resolve(".git").toFile)
      repository.create()
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

    override def info: F[Path] = blocking(repository.getWorkTree.toPath)

    override def verify(): F[Either[GitError, Unit]] =
      blocking:
        val status = Git.wrap(repository).status().call()
        Either.cond(status.isClean, (), GitError.RepositoryIsDirty)

    override def addFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]] =
      blocking:
        val git        = new Git(repository)
        val addCommand = git.add()
        files
          .map(file => Repository.stripWorkDir(repository.getWorkTree, file.toFile))
          .traverse(file => Try(addCommand.addFilepattern(file)).toEither)
        addCommand.call()

        git
          .commit()
          .setMessage(message)
          .call()
          .asRight
