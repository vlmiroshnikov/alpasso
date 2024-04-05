package alpasso.daeamon.gpg

import cats.*
import cats.effect.*
import cats.syntax.all.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.{PGPKeyPair, PGPPrivateKey, PGPPublicKey}
import org.bouncycastle.openpgp.operator.jcajce.*

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.sys.process.*


trait GpgShell[F[_]]:
  def listKeys(): F[RawKeyInfo]
  def loadKeyPair(key: String, pass: String): F[Result[PGPKeyPair]]

object GpgShell:

  def make[F[_] : Sync]: GpgShell[F] = new Impl[F]()

  class Impl[F[_]](using F: Sync[F]) extends GpgShell[F]:
    import F.blocking

    override def listKeys(): F[RawKeyInfo]                                                 = ???
    override def loadKeyPair(keyId: String, pass: String): F[Result[PGPKeyPair]] =
      for
        publicKey <- blocking(
            Process(
              s"gpg --batch --yes --passphrase='${pass}' --pinentry-mode loopback --armor --export ${keyId}"
            ).!!
          )
        privateKey <-
          blocking(
            Process(
              s"gpg --batch --yes --passphrase='${pass}' --pinentry-mode loopback --armor --export-secret-keys ${keyId}"
            ).!!
          )
      yield
        val pubKey = readPublicKey(publicKey).get // todo err handle
        val privK = readSecretKey(privateKey, pubKey.getKeyID, pass).get
        (new PGPKeyPair(pubKey, privK)).asRight


  private def readSecretKey(source: String, keyId: Long, passCode: String): Option[PGPPrivateKey] =
    val bis = new ByteArrayInputStream(source.getBytes)
    val pgpSec =
      new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(bis),
                                     new JcaKeyFingerprintCalculator()
      )

    val builder = new JcePBESecretKeyDecryptorBuilder()
      .setProvider(BouncyCastleProvider.PROVIDER_NAME)
      .build(passCode.toCharArray)

    val ring = pgpSec.getKeyRings.asScala.toList
    ring
      .collectFirstSome(_.getSecretKeys.asScala.find(_.getKeyID == keyId))
      .map(sk => sk.extractPrivateKey(builder))

  private def readPublicKey(source: String): Option[PGPPublicKey] =
    val bis = new ByteArrayInputStream(source.getBytes)
    val pgp =
      new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(bis),
                                     new JcaKeyFingerprintCalculator()
      )
    val ring = pgp.getKeyRings.asScala.toList
    ring.collectFirstSome(_.getPublicKeys.asScala.find(_.isEncryptionKey))

