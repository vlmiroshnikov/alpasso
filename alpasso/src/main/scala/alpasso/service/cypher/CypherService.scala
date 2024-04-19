package alpasso.service.cypher

import scala.concurrent.duration.*
import scala.sys.process.*

import cats.*
import cats.data.EitherT
import cats.effect.*
import cats.syntax.all.*

import logstage.LogIO
import logstage.LogIO.log

trait CypherService[F[_]]:
  def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]
  def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]

object CypherService:

  private class GpgImpl[F[_]: Functor](client: GpgClient[F], fg: String) extends CypherService[F]:

    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      EitherT(client.encrypt(fg, RawData.from(raw))).map(_.underlying).value

    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      EitherT(client.decrypt(fg, EncryptedData.from(raw))).map(_.underlying).value

  def empty[F[_]: Applicative]: CypherService[F] = new CypherService[F]:
    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure
    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure

  def makeGpgCypher[F[_]: Async: Logger](fg: String, enterPass: () => F[String]): F[Either[Unit, CypherService[F]]] =
    val client = GpgClient.make[F]()

    def fork =
      for
        _ <- log.info("Run fork")
        _ <-
          Sync[F].blocking(
            Process("/Users/v.miroshnikov/workspace/alpasso/alpasso/target/universal/stage/bin/alpasso daemon")
              .run(BasicIO(false, ProcessLogger(out => println(out))).daemonized())
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
