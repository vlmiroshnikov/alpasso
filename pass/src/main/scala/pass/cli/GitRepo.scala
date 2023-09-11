package pass.cli

import scala.util.*
import cats.*
import cats.syntax.all.*
import cats.effect.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.io.File
import java.nio.file.*

trait GitRepo[F[_]]:
  def info: F[Path]
  def addFiles(files: List[File], message: String): F[Unit]

object GitRepo:

  def openExists[F[_]: Sync](repoDir: Path): Resource[F, GitRepo[F]] = Resource
    .fromAutoCloseable(Sync[F].blocking {
      val builder = new FileRepositoryBuilder()
      builder
        .setGitDir(repoDir.resolve(".git").toFile)
        .readEnvironment() // scan environment GIT_* variables
        .findGitDir() // scan up the file system tree
        .setMustExist(true)
        .build()
    })
    .map(Impl(_))

  def create[F[_]: Sync](repoDir: Path): Resource[F, GitRepo[F]] = Resource
    .fromAutoCloseable(Sync[F].blocking {
      val home       = Files.createDirectory(repoDir)
      val repository = FileRepositoryBuilder.create(home.resolve(".git").toFile)
      repository.create()
      println(repository.getDirectory)
      repository
    })
    .map(Impl(_))

  class Impl[F[_]: Sync](repository: Repository) extends GitRepo[F]:
    override def info: F[Path] = Sync[F].blocking(repository.getDirectory.toPath)

    override def addFiles(files: List[File], message: String): F[Unit] =
      Sync[F].blocking:
        val git = new Git(repository)
        val cmd = git.add()
        files.traverse(file => Try(cmd.addFilepattern(file.toString)).toEither)
        cmd.call()

        git.commit().setMessage(message).call()
