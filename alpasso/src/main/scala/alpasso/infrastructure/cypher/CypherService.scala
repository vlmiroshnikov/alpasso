package alpasso.infrastructure.cypher

import java.io.*

import scala.sys.process.*

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*

enum CypherErr:
  case InvalidCypher
  case SessionExpired
  case AgentError(message: String)
  case KeyInputError

type Result[A] = Either[CypherErr, A]

type Bytes = Array[Byte]

trait CypherService[F[_]]:
  def encrypt(raw: Bytes): F[Result[Bytes]]
  def decrypt(raw: Bytes): F[Result[Bytes]]

object CypherService:

  private class GpgImpl[F[_]: {Sync as S}](fg: Recipient) extends CypherService[F]:

    import S.blocking

    private val silentLogger =
      ProcessLogger(fout => println(s"FOUT: ${fout}"), ferr => println(s"FERRL ${ferr}"))

    override def encrypt(raw: Bytes): F[Result[Bytes]] =
      val bis     = ByteArrayInputStream(raw)
      val bos     = ByteArrayOutputStream()
      val encrypt =
        Process("gpg", Seq("--encrypt", "--quiet", "--recipient", fg, "--armor")) #< bis #> bos

      val result =
        for rcode <- blocking(encrypt.!(silentLogger))
        yield Either.cond(rcode == 0, bos.toByteArray, CypherErr.InvalidCypher)

      EitherT(result).value

    override def decrypt(raw: Bytes): F[Result[Bytes]] =
      val bis     = ByteArrayInputStream(raw)
      val bos     = ByteArrayOutputStream()
      val decrypt =
        Process("gpg", Seq("--decrypt", "--quiet", "--recipient", fg, "--armor")) #< bis #> bos

      val result =
        for rcode <- blocking(decrypt.!(silentLogger))
        yield Either.cond(rcode == 0, bos.toByteArray, CypherErr.InvalidCypher)

      EitherT(result).value

  def empty[F[_]: Applicative]: CypherService[F] = new CypherService[F]:
    override def encrypt(raw: Bytes): F[Result[Bytes]] = raw.asRight.pure
    override def decrypt(raw: Bytes): F[Result[Bytes]] = raw.asRight.pure

  def gpg[F[_]: Sync](fg: Recipient): CypherService[F] = GpgImpl[F](fg)

  def masterKey[F[_]: Async: Console](
      key: Array[Byte],
      manager: AesAgentManager[F]): Resource[F, CypherService[F]] =
    manager.start(key).map(client => MasterKeyImpl[F](client))

  private class MasterKeyImpl[F[_]: Sync](client: AesAgentClient[F])
      extends CypherService[F]:

    override def encrypt(raw: Bytes): F[Result[Bytes]] =
      client.encrypt(raw).map {
        case Right(encrypted) => encrypted.asRight
        case Left(AesAgentErr.InitializationFailed(msg)) if msg.contains("expired") =>
          CypherErr.SessionExpired.asLeft
        case Left(err) =>
          CypherErr.AgentError(err.toString).asLeft
      }

    override def decrypt(raw: Bytes): F[Result[Bytes]] =
      client.decrypt(raw).map {
        case Right(decrypted) => decrypted.asRight
        case Left(AesAgentErr.InitializationFailed(msg)) if msg.contains("expired") =>
          CypherErr.SessionExpired.asLeft
        case Left(err) =>
          CypherErr.AgentError(err.toString).asLeft
      }

