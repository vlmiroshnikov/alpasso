package alpasso.commands

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.domain.*
import alpasso.infrastructure.cypher.*
import alpasso.infrastructure.filesystem.*
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.{ GitError, GitRepo }
import alpasso.infrastructure.session.models.*
import alpasso.presentation.{ *, given }
import alpasso.shared.errors.Err
import alpasso.shared.models.{ Node, Package, Result, SemVer }
import alpasso.shared.syntax.*

import glass.*

def historyLog[F[_]: Sync](configuration: RepositoryConfiguration): F[Result[HistoryLogView]] =
  GitRepo.openExists(configuration.repoDir).use { git =>
    git.history().liftE[Err].map(v => HistoryLogView.from(v.commits)).value
  }

trait Command[F[_]]:

  def create(
      name: SecretName,
      payload: SecretPayload,
      meta: Option[SecretMetadata]): F[Result[SecretView]]

  def patch(
      name: SecretName,
      payload: Option[SecretPayload],
      meta: Option[SecretMetadata]): F[Result[SecretView]]

  def remove(name: SecretName): F[Result[SecretView]]
  def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]]

object Command:

  def make[F[_]: { Async }](config: RepositoryConfiguration): Command[F] =
    val cs = config.cypherAlg match
      case CypherAlg.Gpg(fingerprint) => CypherService.gpg(fingerprint)

    val reader  = RepositoryReader.make(config, cs)
    val mutator = RepositoryMutator.make(config)
    Impl[F](cs, reader, mutator)

  private class Impl[F[_]: { Async }](
      cs: CypherService[F],
      reader: RepositoryReader[F],
      mutator: RepositoryMutator[F])
      extends Command[F]:

    override def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]] =
      def predicate(p: Package): Boolean =
        filter match
          case SecretFilter.Grep(pattern) => p.name.asPath.toString.contains(pattern)
          case SecretFilter.Empty         => true

      def load(s: Secret[Locations]) =
        reader.loadFully(s).liftE[Err].nested.map((d, m) => (d.into(), m.into())).value

      val result = for
        rawTree <- reader.walkTree.liftE[Err]
        tree    <- rawTree.traverse(_.traverse(load))
      yield cutTree(tree, predicate).map(_.traverse(b => Id(b.map(_.into()))))

      result.value

    private def lookup(name: SecretName): EitherT[F, Err, Option[Secret[Locations]]] =
      for catalog <- reader.walkTree.liftE[Err]
      yield catalog.find(_.fold(false, _.name == name)).flatMap(_.toOption)

    override def create(
        name: SecretName,
        payload: SecretPayload,
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val rmd = meta.map(RawMetadata.from).getOrElse(RawMetadata.empty)
      val result =
        for
          data      <- cs.encrypt(payload.rawData).liftE[Err]
          locations <- mutator.create(name, RawSecretData.fromRaw(data), rmd).liftE[Err]
        yield SecretView(name, None, meta.map(_.into()))

      result.value

    override def remove(name: SecretName): F[Result[SecretView]] =
      val result = for
        exists <- lookup(name)
        _      <- exists.toRight(RepositoryErr.NotFound(name)).pure[F].liftE[Err]
        _      <- mutator.remove(name).liftE[Err]
      yield SecretView(name, None, None)

      result.value

    override def patch(
        name: SecretName,
        payload: Option[SecretPayload],
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val result =
        for
          exists    <- lookup(name)
          locations <- exists.toRight(RepositoryErr.NotFound(name)).pure[F].liftE[Err]

          toUpdate <- reader.loadFully(locations).liftE[Err]

          rsd = payload.map(_.rawData).getOrElse(toUpdate.payload._1.byteArray)
          rmd = meta.map(RawMetadata.from).getOrElse(toUpdate.payload._2)

          sec <- cs.encrypt(rsd).liftE[Err]
          _   <- mutator.update(name, RawSecretData.fromRaw(sec), rmd).liftE[Err]
        yield SecretView(name, None, None)

      result.value
end Command
