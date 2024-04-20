package alpasso

import alpasso.daeamon.gpg.{GpgService, GpgShell, SessionPayload, SessionStorage}
import alpasso.shared.SemVer
import cats.data.EitherT
import cats.effect.kernel.Sync

import java.time.OffsetDateTime
import cats.syntax.all.*
import cats.effect.{ExitCode, IO, IOApp}
import sttp.tapir.*
import sttp.tapir.json.circe.*
import io.circe.derivation.*
import org.http4s.HttpRoutes
import org.http4s.server.Router
import org.http4s.blaze.server.BlazeServerBuilder
import sttp.tapir.*
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.redoc.RedocUIOptions
import sttp.tapir.redoc.bundle.RedocInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import logstage.*
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.Security
import java.util.Base64
import scala.concurrent.duration.*

object model:
  given Configuration = Configuration.default.withSnakeCaseMemberNames

  case class CheckRequest(keyId: String) derives ConfiguredCodec
  object CheckRequest:
    given Schema[CheckRequest] = Schema.derived

  case class CheckResponse()  derives ConfiguredCodec
  object CheckResponse:
    given Schema[CheckResponse] = Schema.derived

  case class GetSessionRequest(keyId: String) derives ConfiguredCodec
  object GetSessionRequest:
    given Schema[GetSessionRequest] = Schema.derived

  case class CreateSessionRequest(keyId: String, pass: String) derives ConfiguredCodec
  object CreateSessionRequest:
    given Schema[CreateSessionRequest] = Schema.derived

  case class SessionDataResponse(keyId: String, created: OffsetDateTime, expired: OffsetDateTime) derives ConfiguredCodec
  object SessionDataResponse:
    given Schema[SessionDataResponse] = Schema.derived

  case class EncryptRequest(keyId: String, base64Payload: String) derives  ConfiguredCodec
  object EncryptRequest:
    given Schema[EncryptRequest] = Schema.derived

  case class EncryptResponse(keyId: String, base64Payload: String)derives ConfiguredCodec
  object EncryptResponse:
    given Schema[EncryptResponse] = Schema.derived

  case class DecryptRequest(keyId: String, base64Payload: String)derives ConfiguredCodec

  object DecryptRequest:
    given Schema[DecryptRequest] = Schema.derived

  case class DecryptResponse(keyId: String, base64Payload: String)derives ConfiguredCodec

  object DecryptResponse:
    given Schema[DecryptResponse] = Schema.derived

val contextPath: List[String] = List("api", "v1")
val docPathPrefix: List[String] = "redoc" :: Nil

enum SessionError:
  case NotFound(key: String)
  case Unknown

object Endpoints:
  import model.*

  val check: PublicEndpoint[Unit, Unit, CheckResponse, Any] =
    endpoint.get.in("gpg" / "health").in(emptyInput).out(jsonBody[CheckResponse])

  val getSession: PublicEndpoint[GetSessionRequest, SessionError, SessionDataResponse, Any] =
    endpoint.post.in("gpg" / "sessions").in(jsonBody[GetSessionRequest]).out(jsonBody[SessionDataResponse])
      .errorOut(
        oneOf[SessionError](
          oneOfVariant(statusCode(StatusCode.NotFound).and(stringBody.mapTo[SessionError.NotFound])),
          oneOfDefaultVariant(emptyOutputAs(SessionError.Unknown))
      ))

  val createSession: PublicEndpoint[CreateSessionRequest, SessionError, SessionDataResponse, Any] =
    endpoint.put.in("gpg" / "sessions").in(jsonBody[CreateSessionRequest]).out(jsonBody[SessionDataResponse])
      .errorOut(
        oneOf[SessionError](
          oneOfVariant(statusCode(StatusCode.NotFound).and(stringBody.mapTo[SessionError.NotFound])),
          oneOfDefaultVariant(emptyOutputAs(SessionError.Unknown))
        ))

  val encrypt: PublicEndpoint[EncryptRequest, Unit, EncryptResponse, Any] =
    endpoint.put.in("gpg" / "encrypt").in(jsonBody[EncryptRequest]).out(jsonBody[EncryptResponse])

  val decrypt: PublicEndpoint[DecryptRequest, Unit, DecryptResponse, Any] =
    endpoint.put.in("gpg" / "encrypt").in(jsonBody[DecryptRequest]).out(jsonBody[DecryptResponse])


object ServerRoutes:
  import Endpoints.*
  import model.*

  def routes(api: ServerAPI[IO]): HttpRoutes[IO] =
    val redocEndpoints = RedocInterpreter(redocUIOptions = RedocUIOptions.default.contextPath(contextPath).pathPrefix(docPathPrefix))
      .fromEndpoints[IO](List(check, getSession, createSession, encrypt, decrypt), "The tapir library", "1.0.0")

    val intpr = Http4sServerInterpreter[IO]()

    val endpoints : List[ServerEndpoint[Any, IO]] = List(
      check.serverLogicPure{
        req =>
          println("HealCheck run")
          CheckResponse().asRight
      },
      getSession.serverLogic[IO]{
        req =>
          EitherT(api.getSession(req.keyId))
            .map(r => SessionDataResponse(req.keyId, OffsetDateTime.now(), OffsetDateTime.now()))
            .value
      },
      createSession.serverLogic[IO]{
        req =>
          EitherT(api.createSession(req.keyId, req.pass))
            .map(r => SessionDataResponse(req.keyId, OffsetDateTime.now(), OffsetDateTime.now()))
            .value
      },
      encrypt.serverLogic[IO] {
        req =>
          EitherT(api.encrypt(req.keyId, Base64.getDecoder.decode(req.base64Payload)))
            .map(r => EncryptResponse(req.keyId, Base64.getEncoder.encodeToString(r)))
            .value
      },
      decrypt.serverLogic[IO] { req =>
        EitherT(api.decrypt(req.keyId, Base64.getDecoder.decode(req.base64Payload)))
          .map(r => DecryptResponse(req.keyId, Base64.getEncoder.encodeToString(r)))
          .value
      },
    )

    intpr.toRoutes(redocEndpoints) <+> intpr.toRoutes(endpoints)


def runDaemon(using log: LogIO[IO]): IO[Unit] =
  val ec = scala.concurrent.ExecutionContext.global
  Security.addProvider(new BouncyCastleProvider())

  SessionStorage.make[IO].map(ServerAPI.make(_)).flatMap { api =>
    BlazeServerBuilder[IO]
      .withExecutionContext(ec)
      .bindHttp(8080, "localhost")
      .withHttpApp(Router(s"/${contextPath.mkString("/")}" -> ServerRoutes.routes(api)).orNotFound)
      .resource
      .use { _ =>
        val path = (contextPath ++ docPathPrefix).mkString("/")
        log.info(s"go to: http://127.0.0.1:8080/$path") *> IO.never
      }
  }


object Daemon extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    given LogIO[IO] = LogIO.fromLogger(IzLogger())
    runDaemon.as(ExitCode.Success)



trait ServerAPI[F[_]]:
  def validate(version: SemVer): F[Either[Unit, Unit]]
  def getSession(keyId: String): F[Either[SessionError, Unit]]
  def createSession(keyId: String, pass: String): F[Either[SessionError, Unit]]

  def encrypt(keyId: String, payload: Array[Byte]): F[Either[Unit, Array[Byte]]]
  def decrypt(keyId: String, payload: Array[Byte]): F[Either[Unit, Array[Byte]]]


object ServerAPI:
  def make[F[_]: Sync](sessions: SessionStorage[F]): ServerAPI[F] =  new ServerAPI[F]:
    override def validate(version: SemVer): F[Either[Unit, Unit]] =
      ().asRight.pure[F]

    override def getSession(keyId: String): F[Either[SessionError, Unit]] =
      sessions.get(keyId).map(_.fold(SessionError.NotFound(keyId).asLeft)(_ => ().asRight))

    override def createSession(keyId: String, pass: String): F[Either[SessionError, Unit]] =
      (for
        kp <- EitherT(GpgShell.make[F].loadKeyPair(keyId, pass))
        _  <- EitherT.liftF(sessions.put(keyId, 5.minutes, SessionPayload(kp)))
      yield ()).leftMap(_ => SessionError.Unknown).value

    override def encrypt(keyId: String, payload: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      (for
        gpg <- EitherT.fromOptionF(sessions.get(keyId), ()).map(v=>GpgService.make(v.keyPair))
        res <-  EitherT(gpg.encrypt(payload))
      yield res).value

    override def decrypt(keyId: String, payload: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      (for
        gpg <- EitherT.fromOptionF(sessions.get(keyId), ()).map(v => GpgService.make(v.keyPair))
        res <- EitherT(gpg.decrypt(payload))
      yield res).value