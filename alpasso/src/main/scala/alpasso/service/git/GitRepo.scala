package alpasso.service.git

import java.nio.file.*

import scala.jdk.CollectionConverters.*
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

type Result[T] = Either[GitError, T]

case class LogRecord(hex: String, comment: String)
case class HistoryLog(commits: List[LogRecord])

trait GitRepo[F[_]]:
  def verify: F[Result[Unit]]
  def commitFiles(files: NonEmptyList[Path], message: String): F[Result[RevCommit]]
  def removeFiles(files: NonEmptyList[Path], message: String): F[Result[RevCommit]]
  def history(): F[Result[HistoryLog]]

object GitRepo:

  def openExists[F[_]: Sync as F](repoDir: Path): Resource[F, GitRepo[F]] =
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

  def createNew[F[_]: Sync](repoDir: Path): Resource[F, GitRepo[F]] = Resource
    .fromAutoCloseable(Sync[F].blocking {
      val repository = FileRepositoryBuilder.create(repoDir.resolve(".git").toFile)
      repository.create()
      repository
    })
    .map(Impl(_))

  private def verify_[F[_]: Sync as S](repository: Repository): F[Either[GitError, Unit]] =
    S.blocking:
      val status = Git.wrap(repository).status().call()
      Either.cond(status.isClean, (), GitError.RepositoryIsDirty)

  class Impl[F[_]: Sync as F](repository: Repository) extends GitRepo[F]:
    import F.blocking

    given Order[Path] = Order.fromComparable[Path]

    override def verify: F[Either[GitError, Unit]] =
      verify_(repository)

    override def history(): F[Result[HistoryLog]] =
      blocking:
        val git   = new Git(repository)
        val items = git.log().call().asScala.toList
        HistoryLog(items.map(v => LogRecord(v.getId.name(), v.getFullMessage))).asRight

    override def commitFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]] =
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

    override def removeFiles(files: NonEmptyList[Path], message: String): F[Either[GitError, RevCommit]] =
      blocking:
        val git   = new Git(repository)
        val rmCmd = git.rm()
        files
          .map(file => Repository.stripWorkDir(repository.getWorkTree, file.toFile))
          .traverse(file => Try(rmCmd.addFilepattern(file)).toEither)
        rmCmd.call()

        git
          .commit()
          .setMessage(message)
          .call()
          .asRight
