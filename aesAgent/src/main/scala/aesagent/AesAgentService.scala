package aesagent

import javax.crypto.Cipher
import javax.crypto.spec.{ IvParameterSpec, SecretKeySpec }

import cats.effect.*
import cats.syntax.all.*

import fs2.grpc.syntax.all.*

class AesAgentServiceImpl[F[_]: Sync](
    sessionManager: SessionManager[F])
    extends aesagent.proto.AesAgentServiceFs2Grpc[F]:

  private val IV_SIZE = 16
  private val ALGORITHM = "AES"
  private val TRANSFORMATION = "AES/CBC/PKCS5Padding"

  override def initialize(
      request: InitializeRequest,
      ctx: fs2.grpc.Metadata): F[InitializeResponse] =
    val key = request.masterKey.toByteArray
    val timeout = if request.sessionTimeoutSeconds == 0 then 900 else request.sessionTimeoutSeconds

    sessionManager.initialize(key, timeout).map {
      case Right(sessionId) =>
        InitializeResponse(
          status = Status.SUCCESS,
          errorMessage = "",
          sessionId = sessionId
        )
      case Left(error) =>
        InitializeResponse(
          status = Status.INVALID_KEY,
          errorMessage = error,
          sessionId = ""
        )
    }

  override def encrypt(
      request: EncryptRequest,
      ctx: fs2.grpc.Metadata): F[EncryptResponse] =
    for
      keyOpt <- sessionManager.getKey
      result <- keyOpt match
                  case None =>
                    sessionManager.isExpired.flatMap { expired =>
                      if expired then
                        EncryptResponse(
                          status = Status.SESSION_EXPIRED,
                          encryptedData = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = "Session expired"
                        ).pure[F]
                      else
                        EncryptResponse(
                          status = Status.SESSION_NOT_INITIALIZED,
                          encryptedData = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = "Session not initialized"
                        ).pure[F]
                    }
                  case Some(key) =>
                    encryptData(request.data.toByteArray, key).map {
                      case Right(encrypted) =>
                        EncryptResponse(
                          status = Status.SUCCESS,
                          encryptedData = com.google.protobuf.ByteString.copyFrom(encrypted),
                          errorMessage = ""
                        )
                      case Left(error) =>
                        EncryptResponse(
                          status = Status.ENCRYPTION_ERROR,
                          encryptedData = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = error
                        )
                    }
    yield result

  override def decrypt(
      request: DecryptRequest,
      ctx: fs2.grpc.Metadata): F[DecryptResponse] =
    for
      keyOpt <- sessionManager.getKey
      result <- keyOpt match
                  case None =>
                    sessionManager.isExpired.flatMap { expired =>
                      if expired then
                        DecryptResponse(
                          status = Status.SESSION_EXPIRED,
                          data = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = "Session expired"
                        ).pure[F]
                      else
                        DecryptResponse(
                          status = Status.SESSION_NOT_INITIALIZED,
                          data = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = "Session not initialized"
                        ).pure[F]
                    }
                  case Some(key) =>
                    decryptData(request.encryptedData.toByteArray, key).map {
                      case Right(decrypted) =>
                        DecryptResponse(
                          status = Status.SUCCESS,
                          data = com.google.protobuf.ByteString.copyFrom(decrypted),
                          errorMessage = ""
                        )
                      case Left(error) =>
                        DecryptResponse(
                          status = Status.DECRYPTION_ERROR,
                          data = com.google.protobuf.ByteString.EMPTY,
                          errorMessage = error
                        )
                    }
    yield result

  override def healthCheck(
      request: HealthCheckRequest,
      ctx: fs2.grpc.Metadata): F[HealthCheckResponse] =
    for
      expired     <- sessionManager.isExpired
      remainingOpt <- sessionManager.getRemainingSeconds
      (status, remaining, error) = if expired then
                                      (SessionStatus.EXPIRED, 0, "Session expired")
                                    else if remainingOpt.isEmpty then
                                      (SessionStatus.NOT_INITIALIZED, 0, "Session not initialized")
                                    else
                                      (SessionStatus.ACTIVE, remainingOpt.get, "")
    yield HealthCheckResponse(
      sessionStatus = status,
      remainingSeconds = remaining,
      errorMessage = error
    )

  private def encryptData(data: Array[Byte], key: Array[Byte]): F[Either[String, Array[Byte]]] =
    Sync[F].blocking {
      try
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher    = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv        = cipher.getIV
        val encrypted = cipher.doFinal(data)
        val result    = new Array[Byte](IV_SIZE + encrypted.length)
        System.arraycopy(iv, 0, result, 0, IV_SIZE)
        System.arraycopy(encrypted, 0, result, IV_SIZE, encrypted.length)
        result.asRight
      catch
        case e: Exception => e.getMessage.asLeft
    }

  private def decryptData(encryptedData: Array[Byte], key: Array[Byte]): F[Either[String, Array[Byte]]] =
    Sync[F].blocking {
      try
        if encryptedData.length < IV_SIZE then
          "Encrypted data too short".asLeft
        else
          val iv        = new Array[Byte](IV_SIZE)
          val encrypted = new Array[Byte](encryptedData.length - IV_SIZE)
          System.arraycopy(encryptedData, 0, iv, 0, IV_SIZE)
          System.arraycopy(encryptedData, IV_SIZE, encrypted, 0, encrypted.length)

          val secretKey = SecretKeySpec(key, ALGORITHM)
          val cipher    = Cipher.getInstance(TRANSFORMATION)
          val ivSpec    = IvParameterSpec(iv)
          cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
          val decrypted = cipher.doFinal(encrypted)
          decrypted.asRight
      catch
        case e: Exception => e.getMessage.asLeft
    }
