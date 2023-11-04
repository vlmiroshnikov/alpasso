package alpasso.service

import java.io.FileInputStream
import java.nio.file.*
import java.nio.file.Path
import java.security.{ PrivateKey, Security }

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.*

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import org.bouncycastle.gpg.SExprParser
import org.bouncycastle.gpg.keybox.*
import org.bouncycastle.gpg.keybox.jcajce.{ JcaKeyBox, JcaKeyBoxBuilder }
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.bc.*
import org.bouncycastle.openpgp.operator.bc.*
import org.bouncycastle.openpgp.operator.jcajce.*
import org.bouncycastle.util.encoders.Hex

enum CryptoErr:
  case Empty

object BCBuilder:

  def userSecretKeyDir(gpgHome: Path): Path = gpgHome.resolve("private-keys-v1.d")

  def findSecretKey[F[_]: Sync](secretDirPath: Path, publicKey: PGPPublicKey, pass: String): F[List[PGPSecretKey]] =
    val calculatorProvider = new JcaPGPDigestCalculatorProviderBuilder().build()
    val passphraseProvider = new JcePBEProtectionRemoverFactory(pass.toCharArray)
    val sparser            = new SExprParser(calculatorProvider)

    def tryParser(keyFile: Path) =
      Resource
        .fromAutoCloseable(Sync[F].blocking(Files.newInputStream(keyFile)))
        .use { is =>
          for
            key <- Sync[F].blocking(sparser.parseSecretKey(is, passphraseProvider, publicKey))
            _ <- Sync[F].raiseWhen(!key.isSigningKey)(
                   new PGPException(s"GPGSecretKey is not a SigningKey: ${key}")
                 )
          yield key
        }
        .attempt

    Files
      .list(secretDirPath)
      .toList
      .asScala
      .toList
      .filter(Files.isRegularFile(_))
      .traverseEither(tryParser)((key, e) => Sync[F].delay(println(s" ${key}  ${e}")))

  def loadPublicKey[F[_]: Sync](kbxPath: Path): Resource[F, KeyBox] =
    Resource
      .fromAutoCloseable(Sync[F].blocking(new FileInputStream(kbxPath.toString)))
      .map(is => new JcaKeyBoxBuilder().build(is))

  def findPKByKeyId(keyId: String, keyBox: KeyBox): Option[PGPPublicKey] =
    keyBox
      .getKeyBlobs
      .asScala
      .toList
      .filter(_.getType == BlobType.OPEN_PGP_BLOB)
      .collectFirstSome { kb =>
        kb.getKeyInformation
          .asScala
          .find(ki => Hex.toHexString(ki.getFingerprint).toUpperCase == keyId)
          .flatMap(ki => getPublicKey(kb, ki.getFingerprint))
      }

  private def getPublicKey(blob: KeyBlob, fingerprint: Array[Byte]): Option[PGPPublicKey] =
    blob match
      case b: PublicKeyRingBlob => Option(b.getPGPPublicKeyRing.getPublicKey(fingerprint))
      case _                    => none

trait Cypher[F[_]]:
  def encrypt(data: Array[Byte]): F[Either[CryptoErr, Array[Byte]]]

object Cypher:

  class BCCypher[F[_]: Monad](keyId: String, pass: String) extends Cypher[F]:
    Security.addProvider(new BouncyCastleProvider())
    override def encrypt(data: Array[Byte]): F[Either[CryptoErr, Array[Byte]]] = ???

import cats.effect.unsafe.implicits.global

@main
def main =
  Security.addProvider(new BouncyCastleProvider())

  val keyId = "C7FE51E3A3790ABE0D9FD172DA874AB03CF06294"
  BCBuilder
    .loadPublicKey[IO](Path.of("/home/vmiroshnikov/.gnupg/pubring.kbx"))
    .use { box =>
      val kb        = BCBuilder.findPKByKeyId(keyId, box)
      val secretDir = BCBuilder.userSecretKeyDir(Path.of("/home/vmiroshnikov/.gnupg"))

      for
        pk <- BCBuilder.findSecretKey[IO](secretDir, kb.get, "$!lentium")
        _  <- IO.println(pk)
      yield ()
    }
    .unsafeRunSync()
