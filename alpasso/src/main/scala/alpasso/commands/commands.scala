package alpasso.commands

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import alpasso.domain.*
import alpasso.infrastructure.cypher.*
import alpasso.infrastructure.filesystem.*
import alpasso.infrastructure.filesystem.RepositoryMutator.StateF
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.GitRepo
import alpasso.presentation.{*, given}
import alpasso.shared.errors.Err
import alpasso.shared.models.{Node, Package, Result}
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
    val mutator: RepositoryMutator[StateF[F, *]] = RepositoryMutator.make(config, cs)
    Impl[F](cs, reader, mutator)

  private class Impl[F[_]: { Async }](
      cs: CypherService[F],
      reader: RepositoryReader[F],
      mutator: RepositoryMutator[StateF[F, *]])
      extends Command[F]:

    private def load(s: Secret[SecretPathEntries]): EitherT[F, Err, Secret[(SecretPayload, SecretMetadata)]] =
      reader.loadFully(s).liftE[Err].nested.map((d, m) => (d.into(), m.into())).value

    override def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]] =
      def predicate(p: Package): Boolean =
        filter match
          case SecretFilter.Grep(pattern) =>
            p.name.asPath.toString.contains(pattern)
          case SecretFilter.Empty =>
            true

      val result = for
        rawTree <- reader.walkTree.liftE[Err]
        tree    <- rawTree.traverse(_.traverse(load))
      yield cutTree(tree, predicate).map(_.traverse(b => Id(b.map(_.into()))))

      result.value

    private def lookupOpt(name: SecretName): EitherT[F, Err, Option[Secret[SecretPathEntries]]] =
      for catalog <- reader.walkTree.liftE[Err]
      yield catalog.find(_.fold(false, _.name == name)).flatMap(_.toOption)

    private def lookup(name: SecretName): EitherT[F, Err, Secret[SecretPathEntries]] =
      for
        exists <- lookupOpt(name)
        result <- exists.toRight(RepositoryErr.NotFound(name)).pure[F].liftE[Err]
      yield result

    override def create(
        name: SecretName,
        payload: SecretPayload,
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val rmd = meta.map(RawMetadata.from).getOrElse(RawMetadata.empty)
      val result =
        for
          //data      <- cs.encrypt(payload.rawData).liftE[Err]
          locations <- mutator.create(name, rmd)
                              .runA(RawSecretData.fromRaw(payload.rawData).some)
                              .liftE[Err]
        yield SecretView(name, None, meta.map(_.into()))

      result.value

    override def remove(name: SecretName): F[Result[SecretView]] =
      val result = for
        _ <- lookup(name)
        _ <- mutator.remove(name).runA(None).liftE[Err]
      yield SecretView(name, None, None)

      result.value

    override def patch(
        name: SecretName,
        payload: Option[SecretPayload],
        meta: Option[SecretMetadata]): F[Result[SecretView]] =
      val result =
        for
          locations <- lookup(name)
          toUpdate  <- reader.loadFully(locations).liftE[Err]

          rsd = payload.map(_.rawData).getOrElse(toUpdate.payload._1.byteArray)
          rmd = meta.map(RawMetadata.from).getOrElse(toUpdate.payload._2)

          //sec <- cs.encrypt(rsd).liftE[Err]
          _   <- mutator.update(name, rmd).runA(RawSecretData.fromRaw(rsd).some).liftE[Err]

          upd <- lookup(name) >>= load
        yield SecretView(name, new String(upd.payload._1.rawData).some, Some(upd.payload._2.into()))

      result.value
end Command
