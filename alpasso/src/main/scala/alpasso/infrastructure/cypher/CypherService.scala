package alpasso.infrastructure.cypher

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import scala.sys.process.*

import cats.*
import cats.data.EitherT
import cats.effect.*
import cats.syntax.all.*

enum CypherErr:
  case InvalidCypher

type Result[A] = Either[CypherErr, A]

trait CypherService[F[_]]:
  def encrypt(raw: Array[Byte]): F[Result[Array[Byte]]]
  def decrypt(raw: Array[Byte]): F[Result[Array[Byte]]]

object CypherService:

  private class GpgImpl[F[_]: {Sync as S}](fg: Recipient) extends CypherService[F]:

    import S.blocking

    private val silentLogger =
      ProcessLogger(fout => println(s"FOUT: ${fout}"), ferr => println(s"FERRL ${ferr}"))

    override def encrypt(raw: Array[Byte]): F[Result[Array[Byte]]] =
      val bis     = ByteArrayInputStream(raw)
      val bos     = ByteArrayOutputStream()
      val encrypt =
        Process("gpg", Seq("--encrypt", "--quiet", "--recipient", fg, "--armor")) #< bis #> bos

      val result =
        for rcode <- blocking(encrypt.!(silentLogger))
        yield Either.cond(rcode == 0, bos.toByteArray, CypherErr.InvalidCypher)

      EitherT(result).value

    override def decrypt(raw: Array[Byte]): F[Result[Array[Byte]]] =
      val bis     = ByteArrayInputStream(raw)
      val bos     = ByteArrayOutputStream()
      val decrypt =
        Process("gpg", Seq("--decrypt", "--quiet", "--recipient", fg, "--armor")) #< bis #> bos

      val result =
        for rcode <- blocking(decrypt.!(silentLogger))
        yield Either.cond(rcode == 0, bos.toByteArray, CypherErr.InvalidCypher)

      EitherT(result).value

  def empty[F[_]: Applicative]: CypherService[F] = new CypherService[F]:
    override def encrypt(raw: Array[Byte]): F[Result[Array[Byte]]] = raw.asRight.pure
    override def decrypt(raw: Array[Byte]): F[Result[Array[Byte]]] = raw.asRight.pure

  def gpg[F[_]: Sync](fg: Recipient): CypherService[F] = GpgImpl[F](fg)

@main
def main(): Unit = {

  val fg: Recipient = Recipient.hex("1287075CBDF42BC4379E4EC61839D663CCE50A32")

  val bis = ByteArrayInputStream("hello 123123123#$!!#!@@!#".getBytes)
  val bos = ByteArrayOutputStream()
  // gpg --encrypt --no-compress  --recipient
  val encrypt = Process("gpg", Seq("--encrypt", "--recipient", fg, "--armor")) #< bis #> bos
  encrypt.!

  val res = bos.toByteArray
  println(new String(res))

  val bis1 = ByteArrayInputStream(bos.toByteArray)
  val bos1 = ByteArrayOutputStream()

  val decrypt = Process("gpg", Seq("--decrypt", "--recipient", fg, "--armor")) #< bis1 #> bos1
  decrypt.!

  println(new String(bos1.toByteArray))
}
