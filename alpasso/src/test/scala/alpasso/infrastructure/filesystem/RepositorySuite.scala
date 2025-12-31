package alpasso.infrastructure.filesystem

import java.nio.file.Files

import cats.effect.IO
import cats.syntax.all.*

import alpasso.domain.{ Secret, SecretName }
import alpasso.infrastructure.cypher.{ CypherAlg, CypherService, Recipient }
import alpasso.infrastructure.filesystem.PersistentModels.RepositoryMetaConfig
import alpasso.infrastructure.filesystem.RepositoryMutator.State
import alpasso.infrastructure.filesystem.models.*
import alpasso.shared.models.SemVer

import weaver.*

object RepositorySuite extends SimpleIOSuite {

  test("RepositoryMutator create should create a new secret") { _ =>
    val secretName = SecretName.of("simple/name").toOption.get
    val recipient  = Recipient.hex("64695F7D212F979D3553AFC5E0D6CE10FBEB0423")

    val config = RepositoryConfiguration(
      repoDir = genRootDir("secrets"),
      cypherAlg = CypherAlg.Gpg(recipient),
      version = SemVer.zero
    )

    val provisioner = RepositoryProvisioner.make(config.repoDir)

    val cs = CypherService.gpg(recipient)

    val mutator = RepositoryMutator.make[IO](config, cs)
    val reader  = RepositoryReader.make[IO](config, cs)

    val metadata   = RawMetadata.of(Map("description" -> "Test secret"))
    val metaConfig = RepositoryMetaConfig(config.version, config.cypherAlg)

    val data  = RawSecretData.fromRaw("secret data".getBytes)

    val spe = SecretPathEntries.from(config.repoDir, secretName)
    for
      _  <- provisioner.provision(metaConfig)
      wr <- mutator.create(secretName, metadata).runA(State.Plain(data))
      rr <- reader.loadFully(Secret(secretName, spe))
    yield expect(wr.isRight) and expect(rr.isRight) and expect(rr.toOption.get.payload._2 == data)
  }
}

def genRootDir(tail: String): RepoRootDir =
  val randomDir = Files.createTempDirectory("alpasso_test_")
  RepoRootDir.fromPath(randomDir.toAbsolutePath.resolve(tail)).toOption.get
