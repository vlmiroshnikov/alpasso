package alpasso.service

import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream, FileInputStream, InputStream, OutputStream}
import java.nio.file.*
import java.nio.file.Path
import java.security.{PrivateKey, Security}
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.*
import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import org.bouncycastle.gpg.keybox.*
import org.bouncycastle.gpg.keybox.jcajce.{JcaKeyBox, JcaKeyBoxBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.bc.*
import org.bouncycastle.openpgp.operator.bc.*
import org.bouncycastle.openpgp.operator.jcajce.*
import org.bouncycastle.util.encoders.Hex
import alpasso.internal.SecretKeys
import org.bouncycastle.bcpg.{ArmoredOutputStream, CompressionAlgorithmTags, SecretKeyPacket, SymmetricKeyAlgorithmTags}
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.{PBESecretKeyDecryptor, PGPDataEncryptorBuilder}
import org.bouncycastle.util.io.Streams

import java.util.Date

enum CryptoErr:
  case Empty

def readSecretKey(source: String, keyId: Long, passCode: String): Option[PGPPrivateKey] =
  val bis = new ByteArrayInputStream(source.getBytes)
  val pgpSec = new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(bis), new JcaKeyFingerprintCalculator())

  val builder = new JcePBESecretKeyDecryptorBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build(passCode.toCharArray)

  val ring = pgpSec.getKeyRings.asScala.toList
  ring.collectFirstSome(_.getSecretKeys.asScala.find(_.getKeyID == keyId))
    .map(sk => sk.extractPrivateKey(builder))


def readPublicKey(source: String): Option[PGPPublicKey] =
  val bis = new ByteArrayInputStream(source.getBytes)
  val pgp = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(bis), new JcaKeyFingerprintCalculator())
  val ring = pgp.getKeyRings.asScala.toList
  ring.collectFirstSome(_.getPublicKeys.asScala.find(_.isEncryptionKey))


def loadKeys[F[_]: Sync](keyId: String, pass: String): F[(PGPPublicKey, PGPPrivateKey)] =
  for
    //publicKey  <- Sync[F].blocking(Process(s"gpg --batch --yes --passphrase='${pass}' --pinentry-mode loopback --armor --export ${keyId}").!!)
    publicKey  <- Sync[F].blocking(Process(s"gpg --armor --export ${keyId}").!!)
    //privateKey <- Sync[F].blocking(Process(s"gpg --batch --yes --passphrase='${pass}' --pinentry-mode loopback --armor --export-secret-keys ${keyId}").!!)
    privateKey <- Sync[F].blocking(Process(s"gpg --armor --export-secret-keys ${keyId}").!!)
//    key <- Sync[F].blocking(SecretKeys.readSecretKey(is, calculatorProvider, passSupplier, publicKey))
  yield
    val pubKey = readPublicKey(publicKey).get
    val privK = readSecretKey(privateKey, pubKey.getKeyID, pass).get
    (pubKey, privK)

import cats.effect.unsafe.implicits.global

def encrypt[F[_]: Sync](source: Array[Byte], pk: PGPPublicKey): F[Array[Byte]] =
  val builder = new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_128)
                  .setWithIntegrityPacket(true)
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME)

  val dgen  = new PGPEncryptedDataGenerator(builder)
  dgen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(pk))

  val lData = new PGPLiteralDataGenerator();
  val bos = new ByteArrayOutputStream()
  (for
     cypherOut <- Resource.fromAutoCloseable(Sync[F].blocking(dgen.open(bos, new Array[Byte](1024))))
     litS      <- Resource.fromAutoCloseable(Sync[F].blocking(lData.open(cypherOut, PGPLiteralData.BINARY, "sample", source.length.toLong, new Date())))
  yield litS).use { out =>
     Sync[F].blocking(out.write(source))
  } *> Sync[F].blocking(bos.toByteArray)

def deco(out: OutputStream, pk: PGPPrivateKey, pkData: PGPPublicKeyEncryptedData)=
  val decryptorFactory = new JcePublicKeyDataDecryptorFactoryBuilder()
    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
    .build(pk)


  val literalData = pkData.getDataStream(decryptorFactory)

  val litFact = new JcaPGPObjectFactory(literalData)
  val litData = litFact.nextObject().asInstanceOf[PGPLiteralData]
  val data = Streams.readAll(litData.getInputStream())

  println(new String(data))
  out.write(data)
  out.close()

  //  if (publicKeyEncryptedData.isIntegrityProtected()) {
  //    if (!publicKeyEncryptedData.verify()) {
  //      throw new PGPException("Message failed integrity check");
  //    }
  //  }

def decrypt[F[_]: Sync](in: InputStream, keyId: String, pk: PGPPrivateKey): Resource[F, Array[Byte]] =
  val encryptedIn = PGPUtil.getDecoderStream(in)
  val pgpObjectFactory = new JcaPGPObjectFactory(encryptedIn)

  val pog = pgpObjectFactory.iterator().asScala.toList

  val pgpEncryptedDataList = pog.collectFirst:
    case dl: PGPEncryptedDataList => dl

  pgpEncryptedDataList match
    case Some(dataList) =>
      val encDataItrLst = dataList.getEncryptedDataObjects.asScala

      val encDataItr = encDataItrLst.collectFirst:
        case data: PGPPublicKeyEncryptedData => data


      val bos = new ByteArrayOutputStream()
      deco(bos, pk, encDataItr.get)
      Resource.pure(bos.toByteArray)

      /*
       InputStream compressedDataStream = new BufferedInputStream(pgpCompressedData.getDataStream());
      JcaPGPObjectFactory pgpCompObjFac = new JcaPGPObjectFactory(compressedDataStream);

      Object message = pgpCompObjFac.nextObject();

      if (message instanceof PGPLiteralData) {
        PGPLiteralData pgpLiteralData = (PGPLiteralData) message;
        InputStream decDataStream = pgpLiteralData.getInputStream();
        IOUtils.copy(decDataStream, clearOut);
        clearOut.close();
      } else if (message instanceof PGPOnePassSignatureList) {
        throw new PGPException("Encrypted message contains a signed message not literal data");
      } else {
        throw new PGPException("Message is not a simple encrypted file - Type Unknown");
      }
      // Performing Integrity check
      if (publicKeyEncryptedData.isIntegrityProtected()) {
        if (!publicKeyEncryptedData.verify()) {
          throw new PGPException("Message failed integrity check");
        }
      }
       */

    case None => ???




@main
def main =
  Security.addProvider(new BouncyCastleProvider())

  val keyId = "C7FE51E3A3790ABE0D9FD172DA874AB03CF06294"
  val pass =  "$!lentium"
  (for
    pair <- loadKeys[IO](keyId, pass)
    _  <-  IO.println(pair._1)
    _  <-  Resource
            .fromAutoCloseable(IO.blocking(Files.newOutputStream(Path.of("enc"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))
            //.flatMap(out => encrypt(, pair._1))
            .use(out =>  encrypt[IO]("sample1".getBytes, pair._1).flatMap(r => IO(out.write(r))))

    //    _  <-  Resource
    //            .fromAutoCloseable(IO.blocking(Files.newOutputStream(Path.of("enc"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))
    //            .flatMap(out => encrypt(out, pair._1))
    //            .use(cin => IO(cin.write("sample".getBytes)))

    _  <-  Resource
      .fromAutoCloseable(IO.blocking(Files.newInputStream(Path.of("enc"), StandardOpenOption.READ)))
      .flatMap(in => decrypt(in, keyId, pair._2))
      .use(out => IO.println(new String(out)))

  yield ()).unsafeRunSync()
