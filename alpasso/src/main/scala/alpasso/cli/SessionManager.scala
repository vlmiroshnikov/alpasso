package alpasso.cli

import java.nio.file.*
import java.nio.file.StandardOpenOption.{ CREATE, TRUNCATE_EXISTING, WRITE }

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*

import io.circe.*
import io.circe.derivation.*
import io.circe.syntax.given

case class Session(path: Path)

trait SessionManager[F[_]]:
  def current: F[Option[Session]]
  def listAll: F[List[Session]]
  def setup(session: Session): F[Unit]

object SessionManager:
  import models.*

  def make[F[_]: Sync]: SessionManager[F] = Impl[F]

  class Impl[F[_]: Sync as S] extends SessionManager[F]:
    import S.blocking

    private def sessionDir = {
      val str = System.getProperty("user.home")
      Path.of(str).toAbsolutePath.resolve(".alpasso")
    }

    private val sessionFile = sessionDir.resolve("sessions")

    val empty = SessionData(current = None, sessions = Nil)

    def readData(): F[Option[SessionData]] =
      if !Files.exists(sessionFile) then
        for
          _ <- blocking(Files.createDirectory(sessionDir))
          _ <- write(empty)
        yield None
      else
        for
          raw <- blocking(Files.readString(sessionFile))
          ctx <- blocking(parser.parse(raw).flatMap(_.as[SessionData]))
        yield ctx.toOption

    def write(data: SessionData): F[Unit] =
      blocking(
        Files.writeString(sessionFile, data.asJson.spaces2, CREATE, TRUNCATE_EXISTING, WRITE)
      )

    def modify(f: SessionData => SessionData): F[Unit] =
      OptionT(readData()).cata(f(empty), f) >>= write

    override def listAll: F[List[Session]] =
      readData().map(_.map(_.sessions).getOrElse(Nil))

    override def setup(session: Session): F[Unit] =
      modify(old =>
        SessionData(current = session.some, sessions = (session :: old.sessions).distinct)
      )

    override def current: F[Option[Session]] =
      readData().map(_.flatMap(_.current))

end SessionManager

object models:
  given Configuration = Configuration.default.withSnakeCaseMemberNames

  case class SessionData(current: Option[Session], sessions: List[Session]) derives ConfiguredCodec

  object SessionData:
    given Encoder[Session] = Encoder.encodeString.contramap(_.path.toString)
    given Decoder[Session] = Decoder.decodeString.map(s => Session(Path.of(s)))
