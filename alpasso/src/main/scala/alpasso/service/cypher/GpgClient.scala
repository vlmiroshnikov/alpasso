package alpasso.service.cypher

import java.time.OffsetDateTime
import scala.concurrent.duration.*
import scala.sys.process.*
import cats.*
import cats.data.EitherT
import cats.effect.*
import cats.syntax.all.*
import alpasso.Endpoints
import alpasso.model.{EncryptRequest, GetSessionRequest}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import logstage.LogIO.log
import sttp.client3.UriContext
import sttp.client3.logging.LoggingBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

import java.util.Base64

type Logger[F[_]] = LogIO[F]

opaque type Fingerprint <: String = String

type ResultF[F[_], S] = F[Either[Unit, S]]

case class Session(fingerprint: String, created: OffsetDateTime, expired: OffsetDateTime)

trait GpgClient[F[_]]:
  def healthCheck(): F[Either[Unit, Unit]]
  def getSession(id: String): F[Either[Unit, Session]]
  def createSession(id: String, pass: String): F[Either[Unit, Session]]
  def encrypt(id: String, raw: Array[Byte]): F[Either[Unit, Array[Byte]]]

object GpgClient:
  private val url: Uri = uri"http://127.0.0.1:8080/api/v1/"
  private val interpreter = SttpClientInterpreter()

  def make[F[_]: Sync : Logger](): GpgClient[F] = new Impl[F]

  private class Impl[F[_]: Sync : Logger] extends GpgClient[F]:
    override def healthCheck(): ResultF[F, Unit] =
      val client = interpreter.toQuickClient(Endpoints.check, Some(url))
      Sync[F].blocking(client(()).bimap(se => (), r => ())).recover(e => ().asLeft)

    override def getSession(id: String): ResultF[F, Session] =
      val client = interpreter.toQuickClient(Endpoints.getSession, Some(url))
      client(GetSessionRequest(id))
        .bimap(se => (), r => Session(r.keyId, r.created, r.expired))
        .pure[F]

    override def createSession(id: String, pass: String): ResultF[F, Session] =
      val client = interpreter.toQuickClient(Endpoints.createSession, Some(url))
      client(GetSessionRequest(id))
        .bimap(se => (), r => Session(r.keyId, r.created, r.expired))
        .pure[F]

    override def encrypt(id: String, raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      val client = interpreter.toQuickClient(Endpoints.encrypt, Some(url))
      client(EncryptRequest(id, Base64.getEncoder.encodeToString(raw)))
        .bimap(se => (), r => Base64.getDecoder.decode(r.base64Payload))
        .pure[F]


@main
def main =
  given Logger[IO] = LogIO.fromLogger(IzLogger())
  val res = CypherService.makeGpgCypher[IO]("C7FE51E3A3790ABE0D9FD172DA874AB03CF06294",
    () => IO.pure("$!lentium")
  )



trait CypherService[F[_]]:
  def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]
  def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]

object CypherService:

  private class GpgImpl[F[_]](client: GpgClient[F], fg: String) extends CypherService[F]:
    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      client.encrypt(fg, raw)

    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = ???

  def empty[F[_]: Applicative]: CypherService[F] = new CypherService[F]:
    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure
    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure

  def makeGpgCypher[F[_]: Async : Logger](fg: String, enterPass: () => F[String]): F[Either[Unit, CypherService[F]]] =
    val client = GpgClient.make[F]()

    def fork =
      for
        _ <- log.info("Run fork")
        _ <-
          Sync[F].blocking(
            Process("/home/vmiroshnikov/workspace/alpasso/alpasso/target/universal/stage/bin/alpasso daemon")
              .run(BasicIO(false, ProcessLogger(_ => ())).daemonized())

          )
        _ <- log.info("try sleep")
        _ <- Temporal[F].sleep(3.seconds)
      yield ()

    def create =
      for
        pass    <- EitherT.liftF(enterPass())
        session <- EitherT(client.createSession(fg, pass))
      yield session

    val res: EitherT[F, Unit, CypherService[F]] =
      for
        _ <- EitherT(client.healthCheck()).redeemWith(e => EitherT.liftF(fork), _ => ().pure)
        _ <- EitherT(client.getSession(fg)).leftFlatMap(_ => create)
      yield new GpgImpl[F](client, fg)

    res.value
