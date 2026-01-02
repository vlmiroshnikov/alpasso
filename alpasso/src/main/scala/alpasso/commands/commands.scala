package alpasso.commands

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*

import alpasso.domain.*
import alpasso.infrastructure.cypher.*
import alpasso.infrastructure.filesystem.*
import alpasso.infrastructure.filesystem.RepositoryMutator.StateF
import alpasso.infrastructure.filesystem.models.*
import alpasso.infrastructure.git.GitRepo
import alpasso.presentation.{ *, given }
import alpasso.shared.errors.Err
import alpasso.shared.models.{ Node, Package, Result }
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

  def make[F[_]: {Sync, Console}](config: RepositoryConfiguration): Command[F] =
    val cs = config.cypherAlg match
      case CypherAlg.Gpg(fingerprint) => CypherService.gpg(fingerprint)

    val reader                                   = RepositoryReader.make(config, cs)
    val mutator: RepositoryMutator[StateF[F, *]] = RepositoryMutator.make(config, cs)
    Impl[F](cs, reader, mutator)

  private class Impl[F[_]: {Sync, Console}](
      cs: CypherService[F],
      reader: RepositoryReader[F],
      mutator: RepositoryMutator[StateF[F, *]])
      extends Command[F]:

    import RepositoryMutator.State

    private def load(
        s: Secret[SecretPathEntries]): EitherT[F, Err, Secret[(SecretPayload, SecretMetadata)]] =
      reader.loadFully(s).liftE[Err].nested.map((d, m) => (d.into(), m.into())).value

    private def loadMeta(
        s: Secret[
          SecretPathEntries
        ]): EitherT[F, Err, Secret[(SecretPathEntries, SecretMetadata)]] =
      reader.loadMeta(s.map(_.meta)).liftE[Err].nested.map(m => (s.payload, m.into())).value

    override def filter(filter: SecretFilter): F[Result[Option[Node[Branch[SecretView]]]]] =
      def predicate(p: Secret[(SecretPathEntries, SecretMetadata)]): Boolean =
        filter match
          case SecretFilter.Grep(pattern) =>
            p.name
              .asPath
              .toString
              .contains(pattern) || p.payload._2.asMap.mkString.contains(pattern)
          case SecretFilter.Empty =>
            true

      val result = for
        rawTree <- reader.walkTree.liftE[Err]
        tree    <- rawTree.traverse(_.traverse(loadMeta))
        cutted = cutTree(tree, predicate)
        filledTree <- cutted match
                        case Some(root) =>
                          root
                            .traverse(_.traverse(s => load(s.map(_._1))))
                            .map(tree => tree.traverse(branch => Id(branch.map(_.into()))).some)
                        case None => EitherT.pure(None)
      yield filledTree

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
      val rmd    = meta.map(RawMetadata.from).getOrElse(RawMetadata.empty)
      val result =
        for locations <- mutator
                           .create(name, rmd)
                           .runA(State.Plain(RawSecretData.fromBytes(payload.byteArray)))
                           .liftE[Err]
        yield SecretView(name, None, meta.map(_.into()))

      result.value

    override def remove(name: SecretName): F[Result[SecretView]] =
      val result = for
        _ <- lookup(name)
        _ <- mutator.remove(name).runA(State.Empty).liftE[Err]
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

          rsd = payload.map(_.byteArray).getOrElse(toUpdate.payload._1.byteArray)
          rmd = meta.map(RawMetadata.from).getOrElse(toUpdate.payload._2)

          _ <- mutator.update(name, rmd).runA(State.Plain(RawSecretData.fromBytes(rsd))).liftE[Err]

          upd <- lookup(name) >>= load
        yield SecretView(name,
                         new String(upd.payload._1.byteArray).some,
                         Some(upd.payload._2.into())
        )

      result.value
end Command
