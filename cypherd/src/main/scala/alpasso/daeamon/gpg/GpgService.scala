package alpasso.daeamon.gpg

import cats.*
import cats.effect.*
import cats.syntax.all.*
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.*
import org.bouncycastle.util.io.Streams

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, OutputStream}
import java.util.Date
import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.*


case class RawKeyInfo()

enum GpgError:
  case InvalidGpgPacket, InvalidDataFormat

type SessionId = String

case class SessionPayload(keyPair: PGPKeyPair)

trait SessionStorage[F[_]]:
  def put(id: SessionId, ttl: FiniteDuration, payload: SessionPayload): F[Unit]
  def get(id: SessionId): F[Option[SessionPayload]]

object SessionStorage:
  def make[F[_]: Sync]: F[SessionStorage[F]] =
    Ref.of(Map.empty[SessionId, (Deadline, SessionPayload)]).map(ref=> Impl[F](ref))

  class Impl[F[_]](data: Ref[F, Map[SessionId, (Deadline, SessionPayload)]]) extends SessionStorage[F] {
    override def put(id: SessionId, ttl: FiniteDuration, payload: SessionPayload): F[Unit] =
      data.update(v => v.updated(id, (ttl.fromNow, payload)))

    override def get(id: SessionId): F[Option[SessionPayload]] =
      data.modify { m =>
        m.get(id) match
          case Some((deadline, value)) if deadline.isOverdue() => m.removed(id) -> None
          case Some((_, value)) => m -> value.some
          case None => m -> None
      }
  }


type Result = [Z] =>> Either[Unit, Z]

trait GpgService[F[_]]:
  def encrypt(raw: Array[Byte]): F[Result[Array[Byte]]]
  def decrypt(raw: Array[Byte]): F[Result[Array[Byte]]]


object GpgService:
  def make[F[_] : Sync](kp: PGPKeyPair): GpgService[F] = Impl[F](kp)

  private class Impl[F[_]](kp: PGPKeyPair)(using F: Sync[F]) extends GpgService[F]:

    import F.blocking
    import Resource.fromAutoCloseable

    override def encrypt(source: Array[Byte]): F[Result[Array[Byte]]] =
      val builder = new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_128)
        .setWithIntegrityPacket(true)
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)

      val dgen = new PGPEncryptedDataGenerator(builder)
      dgen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(kp.getPublicKey))

      val lData = new PGPLiteralDataGenerator();
      val bos = new ByteArrayOutputStream()
      (for
        cypherOut <- fromAutoCloseable(blocking(dgen.open(bos, new Array[Byte](1024))))
        litS <- fromAutoCloseable(blocking(lData.open(cypherOut, PGPLiteralData.BINARY, "data", source.length.toLong, new Date())))
      yield litS).use(out => blocking(out.write(source))) *> blocking(bos.toByteArray.asRight)

    override def decrypt(raw: Array[Byte]): F[Result[Array[Byte]]] =
      val encryptedIn = PGPUtil.getDecoderStream(new ByteArrayInputStream(raw)) // todo ERR
      val pgpObjectFactory = new JcaPGPObjectFactory(encryptedIn)

      val pog = pgpObjectFactory.iterator().asScala.toList

      val pgpEncryptedDataList = pog.collectFirst:
        case dl: PGPEncryptedDataList => dl

      pgpEncryptedDataList match
        case Some(dataList) =>
          val encDataItrLst = dataList.getEncryptedDataObjects.asScala

          val encDataItr = encDataItrLst.collectFirst:
            case data: PGPPublicKeyEncryptedData => data

          F.pure(deco(kp.getPrivateKey, encDataItr.get).asRight)

        /*
        // Performing Integrity check
        if (publicKeyEncryptedData.isIntegrityProtected()) {
          if (!publicKeyEncryptedData.verify()) {
            throw new PGPException("Message failed integrity check");
          }
        }
        */

        case None => ???

    private def deco(pk: PGPPrivateKey, pkData: PGPPublicKeyEncryptedData): Array[Byte] =
      val decryptorFactory = new JcePublicKeyDataDecryptorFactoryBuilder()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .build(pk)

      val literalData = pkData.getDataStream(decryptorFactory)

      val litFact = new JcaPGPObjectFactory(literalData)
      val litData = litFact.nextObject().asInstanceOf[PGPLiteralData]
      Streams.readAll(litData.getInputStream)
