package alpasso.infrastructure.filesystem

import cats.effect.IO
import cats.syntax.all.*
import weaver.*
import alpasso.domain.SecretName
import alpasso.infrastructure.cypher.{CypherAlg, CypherService, Recipient}
import alpasso.infrastructure.filesystem.models.*
import alpasso.shared.models.SemVer

import java.nio.file.Paths

object RepositoryMutatorSuite extends SimpleIOSuite {

  test("RepositoryMutator create should create a new secret") { _ =>
    val secretName = SecretName.of("simple/name").toOption.get
    val recipient = Recipient.hex("7BC332CCA78CBA3917A3DA2CAFD637E1298147A3")

    val config = RepositoryConfiguration(
      repoDir = RepoRootDir.fromPath(Paths.get(".tmp").toAbsolutePath).toOption.get,
      cypherAlg = CypherAlg.Gpg(recipient),
      version = SemVer.zero
    )

    val cs = CypherService.gpg(recipient)

    val mutator = RepositoryMutator.make[IO](config, cs)

    val metadata = RawMetadata.of(Map("description" -> "Test secret"))

    mutator.create(secretName, metadata)
      .runA(Some(RawSecretData.fromRaw("secret data".getBytes)))
      .map(result => expect(result.isRight))

  }
}