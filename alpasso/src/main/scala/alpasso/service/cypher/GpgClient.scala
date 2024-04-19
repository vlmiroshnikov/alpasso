package alpasso.service.cypher

import java.time.OffsetDateTime
import java.util.Base64

import cats.*
import cats.effect.*
import cats.syntax.all.*

import alpasso.Endpoints
import alpasso.model.{ DecryptRequest, EncryptRequest, GetSessionRequest }

import izumi.logstage.api.IzLogger
import logstage.LogIO
import sttp.client3.UriContext
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

type Logger[F[_]] = LogIO[F]

opaque type RawData <: Array[Byte] = Array[Byte]

object RawData:
  def from(data: Array[Byte]): RawData = data

  extension (x: RawData) def underlying: Array[Byte] = x

opaque type EncryptedData <: Array[Byte] = Array[Byte]

object EncryptedData:
  def from(data: Array[Byte]): EncryptedData = data

  extension (x: EncryptedData) def underlying: Array[Byte] = x

type ResultF[F[_], S] = F[Either[Unit, S]]

case class SessionInfo(fingerprint: String, created: OffsetDateTime, expired: OffsetDateTime)

trait GpgClient[F[_]]:
  def healthCheck(): F[Either[Unit, Unit]]
  def getSession(id: String): F[Either[Unit, SessionInfo]]
  def createSession(id: String, pass: String): F[Either[Unit, SessionInfo]]

  def encrypt(id: String, raw: RawData): F[Either[Unit, EncryptedData]]
  def decrypt(id: String, data: EncryptedData): F[Either[Unit, RawData]]

object GpgClient:
  private val url: Uri    = uri"http://127.0.0.1:8080/api/v1/"
  private val interpreter = SttpClientInterpreter()

  def make[F[_]: Sync: Logger](): GpgClient[F] = new Impl[F]

  private class Impl[F[_]: Sync: Logger] extends GpgClient[F]:

    override def healthCheck(): ResultF[F, Unit] =
      val client = interpreter.toQuickClient(Endpoints.check, Some(url))
      Sync[F].blocking(client(()).bimap(se => (), r => ())).recover(e => ().asLeft)

    override def getSession(id: String): ResultF[F, SessionInfo] =
      val client = interpreter.toQuickClient(Endpoints.getSession, Some(url))
      client(GetSessionRequest(id))
        .bimap(se => (), r => SessionInfo(r.keyId, r.created, r.expired))
        .pure[F]

    override def createSession(id: String, pass: String): ResultF[F, SessionInfo] =
      val client = interpreter.toQuickClient(Endpoints.createSession, Some(url))
      client(GetSessionRequest(id))
        .bimap(se => (), r => SessionInfo(r.keyId, r.created, r.expired))
        .pure[F]

    override def encrypt(id: String, raw: RawData): F[Either[Unit, EncryptedData]] =
      val client = interpreter.toQuickClient(Endpoints.encrypt, Some(url))
      client(EncryptRequest(id, Base64.getEncoder.encodeToString(raw)))
        .bimap(se => (), r => EncryptedData.from(Base64.getDecoder.decode(r.base64Payload)))
        .pure[F]

    override def decrypt(id: String, data: EncryptedData): F[Either[Unit, RawData]] =
      val client = interpreter.toQuickClient(Endpoints.decrypt, Some(url))
      client(DecryptRequest(id, Base64.getEncoder.encodeToString(data)))
        .bimap(se => (), r => RawData.from(Base64.getDecoder.decode(r.base64Payload)))
        .pure[F]

@main
def main =
  given Logger[IO] = LogIO.fromLogger(IzLogger())
  val res = CypherService
    .makeGpgCypher[IO]("E59532DF27540224AF6A37CF0122EF2757E59DB9", () => IO.pure("$!lentium"))
