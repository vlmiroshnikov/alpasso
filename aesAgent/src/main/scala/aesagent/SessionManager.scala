package aesagent

import java.util.UUID

import cats.effect.*
import cats.syntax.all.*

import scala.concurrent.duration.*

final case class Session(
    key: Array[Byte],
    sessionId: String,
    expiresAt: Long
)

trait SessionManager[F[_]]:
  def initialize(key: Array[Byte], timeoutSeconds: Int): F[Either[String, String]]
  def getKey: F[Option[Array[Byte]]]
  def isExpired: F[Boolean]
  def getRemainingSeconds: F[Option[Int]]
  def clear: F[Unit]

object SessionManager:

  def make[F[_]: Sync: Temporal]: F[SessionManager[F]] =
    for
      sessionRef <- Ref.of[F, Option[Session]](None)
      timer      <- Temporal[F].sleep(1.second).foreverM.start
    yield Impl[F](sessionRef, timer)

  private class Impl[F[_]: Sync: Temporal](
      sessionRef: Ref[F, Option[Session]],
      timer: Fiber[F, Throwable, Unit])
      extends SessionManager[F]:

    override def initialize(key: Array[Byte], timeoutSeconds: Int): F[Either[String, String]] =
      if key.length != 32 then
        "Invalid key size: expected 32 bytes for AES-256".asLeft.pure[F]
      else
        for
          now     <- Temporal[F].realTimeInstant.map(_.toEpochMilli)
          expires = now + (timeoutSeconds * 1000L)
          sessionId = UUID.randomUUID().toString
          session = Session(key.clone(), sessionId, expires)
          _       <- sessionRef.set(session.some)
        yield sessionId.asRight

    override def getKey: F[Option[Array[Byte]]] =
      for
        sessionOpt <- sessionRef.get
        now        <- Temporal[F].realTimeInstant.map(_.toEpochMilli)
        result <- sessionOpt match
                    case Some(session) if now < session.expiresAt =>
                      session.key.clone().some.pure[F]
                    case Some(_) =>
                      sessionRef.set(None) *> none[Array[Byte]].pure[F]
                    case None =>
                      none[Array[Byte]].pure[F]
      yield result

    override def isExpired: F[Boolean] =
      for
        sessionOpt <- sessionRef.get
        now        <- Temporal[F].realTimeInstant.map(_.toEpochMilli)
        result <- sessionOpt match
                    case Some(session) if now >= session.expiresAt =>
                      sessionRef.set(None) *> true.pure[F]
                    case Some(_) => false.pure[F]
                    case None    => true.pure[F]
      yield result

    override def getRemainingSeconds: F[Option[Int]] =
      for
        sessionOpt <- sessionRef.get
        now        <- Temporal[F].realTimeInstant.map(_.toEpochMilli)
        result <- sessionOpt match
                    case Some(session) if now < session.expiresAt =>
                      val remaining = ((session.expiresAt - now) / 1000).toInt
                      remaining.some.pure[F]
                    case Some(_) =>
                      sessionRef.set(None) *> none[Int].pure[F]
                    case None =>
                      none[Int].pure[F]
      yield result

    override def clear: F[Unit] =
      sessionRef.set(None)
