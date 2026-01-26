package alpasso.infrastructure.cypher

import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*

import java.nio.charset.StandardCharsets

object KeyInput:

  def readMasterKey[F[_]: Sync: Console]: F[Either[CypherErr, Array[Byte]]] =
    for
      _      <- Console[F].println("Enter master key (32 bytes, hex format or raw):")
      input  <- Console[F].readLine
      result <- parseKey(input)
    yield result

  private def parseKey[F[_]: Sync](input: String): F[Either[CypherErr, Array[Byte]]] =
    Sync[F].delay {
      val trimmed = input.trim
      if trimmed.isEmpty then
        CypherErr.KeyInputError.asLeft
      else if trimmed.length == 64 && trimmed.matches("[0-9a-fA-F]+") then
        // Hex format (64 hex chars = 32 bytes)
        try
          val bytes = new Array[Byte](32)
          for i <- 0 until 32
          do
            val hexByte = trimmed.substring(i * 2, i * 2 + 2)
            bytes(i) = Integer.parseInt(hexByte, 16).toByte
          bytes.asRight
        catch
          case _: Exception => CypherErr.KeyInputError.asLeft
      else if trimmed.length == 32 then
        // Raw bytes (32 characters)
        trimmed.getBytes(StandardCharsets.UTF_8).asRight
      else
        CypherErr.KeyInputError.asLeft
    }
