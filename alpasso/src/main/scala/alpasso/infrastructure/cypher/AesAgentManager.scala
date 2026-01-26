package alpasso.infrastructure.cypher

import java.io.File
import java.net.InetSocketAddress

import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*

import fs2.grpc.syntax.all.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder

import scala.sys.process.*

import aesagent.proto.*

enum AesAgentErr:
  case ProcessStartFailed(message: String)
  case ConnectionFailed(message: String)
  case InitializationFailed(message: String)

trait AesAgentClient[F[_]]:
  def initialize(key: Array[Byte], timeoutSeconds: Int): F[Either[AesAgentErr, String]]
  def encrypt(data: Array[Byte]): F[Either[AesAgentErr, Array[Byte]]]
  def decrypt(encryptedData: Array[Byte]): F[Either[AesAgentErr, Array[Byte]]]
  def healthCheck: F[Either[AesAgentErr, (SessionStatus, Int)]]

trait AesAgentManager[F[_]]:
  def start(key: Array[Byte]): Resource[F, AesAgentClient[F]]
  def stop: F[Unit]

object AesAgentManager:

  private val PORT = 50051
  private val HOST = "127.0.0.1"

  def make[F[_]: Async: Console]: AesAgentManager[F] =
    Impl[F]()

  private class Impl[F[_]: Async: Console]() extends AesAgentManager[F]:

    private def findAgentBinary: F[Option[String]] =
      Sync[F].delay {
        // Try to find aes-agent binary
        val possiblePaths = List(
          "./aesAgent/target/native-image/aes-agent",
          "../aesAgent/target/native-image/aes-agent",
          "aes-agent"
        )
        possiblePaths.find { path =>
          new File(path).exists || {
            try Process("which", Seq(path)).! == 0
            catch case _: Exception => false
          }
        }
      }

    override def start(key: Array[Byte]): Resource[F, AesAgentClient[F]] =
      Resource.make {
        for
          binaryOpt <- findAgentBinary
          binary <- binaryOpt match
                      case Some(b) => b.pure[F]
                      case None    =>
                        Sync[F].raiseError[String](
                          new RuntimeException("aes-agent binary not found")
                        )
          process <- Sync[F].delay(Process(binary).run())
          _       <- Temporal[F].sleep(1.second) // Wait for server to start
          channel <- NettyChannelBuilder
                       .forAddress(new InetSocketAddress(HOST, PORT))
                       .usePlaintext()
                       .build[F]
          client = new AesAgentClientImpl[F](channel)
          initResult <- client.initialize(key, 900)
          _ <- initResult match
                 case Left(err) =>
                   Sync[F].raiseError(new RuntimeException(s"Failed to initialize: $err"))
                 case Right(_) => Sync[F].unit
        yield (process, channel, client)
      } { case (process, channel, _) =>
        for
          _ <- Sync[F].delay(process.destroy())
          _ <- channel.shutdown[F]
        yield ()
      }.map(_._3)

  private class AesAgentClientImpl[F[_]: Async](
      channel: io.grpc.Channel)
      extends AesAgentClient[F]:

    private val stub = aesagent.proto.AesAgentServiceFs2Grpc.stub[F](channel)

    override def initialize(
        key: Array[Byte],
        timeoutSeconds: Int): F[Either[AesAgentErr, String]] =
      val request = InitializeRequest(
        masterKey = com.google.protobuf.ByteString.copyFrom(key),
        sessionTimeoutSeconds = timeoutSeconds
      )
      stub.initialize(request, fs2.grpc.Metadata.empty).map { response =>
        response.status match
          case Status.SUCCESS =>
            response.sessionId.asRight
          case _ =>
            AesAgentErr.InitializationFailed(response.errorMessage).asLeft
      }

    override def encrypt(data: Array[Byte]): F[Either[AesAgentErr, Array[Byte]]] =
      val request = EncryptRequest(
        data = com.google.protobuf.ByteString.copyFrom(data)
      )
      stub.encrypt(request, fs2.grpc.Metadata.empty).map { response =>
        response.status match
          case Status.SUCCESS =>
            response.encryptedData.toByteArray.asRight
          case Status.SESSION_EXPIRED =>
            AesAgentErr.InitializationFailed("Session expired").asLeft
          case Status.SESSION_NOT_INITIALIZED =>
            AesAgentErr.InitializationFailed("Session not initialized").asLeft
          case _ =>
            AesAgentErr.InitializationFailed(response.errorMessage).asLeft
      }

    override def decrypt(encryptedData: Array[Byte]): F[Either[AesAgentErr, Array[Byte]]] =
      val request = DecryptRequest(
        encryptedData = com.google.protobuf.ByteString.copyFrom(encryptedData)
      )
      stub.decrypt(request, fs2.grpc.Metadata.empty).map { response =>
        response.status match
          case Status.SUCCESS =>
            response.data.toByteArray.asRight
          case Status.SESSION_EXPIRED =>
            AesAgentErr.InitializationFailed("Session expired").asLeft
          case Status.SESSION_NOT_INITIALIZED =>
            AesAgentErr.InitializationFailed("Session not initialized").asLeft
          case _ =>
            AesAgentErr.InitializationFailed(response.errorMessage).asLeft
      }

    override def healthCheck: F[Either[AesAgentErr, (SessionStatus, Int)]] =
      val request = HealthCheckRequest()
      stub.healthCheck(request, fs2.grpc.Metadata.empty).map { response =>
        if response.sessionStatus == SessionStatus.ACTIVE then
          (response.sessionStatus, response.remainingSeconds).asRight
        else
          AesAgentErr.InitializationFailed(response.errorMessage).asLeft
      }

  override def stop: F[Unit] = Sync[F].unit
