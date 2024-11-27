package alpasso.service.cypher

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import scala.sys.process.*

import cats.*
import cats.data.EitherT
import cats.effect.*
import cats.syntax.all.*

import alpasso.service.fs.repo.Logger
import alpasso.service.fs.repo.model.CryptoAlg

import logstage.LogIO
import logstage.LogIO.log

trait CypherService[F[_]]:
  def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]
  def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]]

object CypherService:

  private class GpgImpl[F[_]: Monad](fg: String) extends CypherService[F]:

    private val silentLogger = ProcessLogger(fout => (), ferr => ())

    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      val bis = ByteArrayInputStream(raw)
      val bos = ByteArrayOutputStream()
      // gpg --encrypt --no-compress  --recipient
      val encrypt = Process("gpg", Seq("--encrypt", "--recipient", fg, "--armor")) #< bis #> bos
      encrypt.!(silentLogger)

      EitherT.pure(bos.toByteArray).value

    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] =
      val bis     = ByteArrayInputStream(raw)
      val bos     = ByteArrayOutputStream()
      val decrypt = Process("gpg", Seq("--decrypt", "--recipient", fg, "--armor")) #< bis #> bos
      decrypt.!(silentLogger)

      EitherT.pure(bos.toByteArray).value

  def empty[F[_]: Applicative]: CypherService[F] = new CypherService[F]:
    override def encrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure
    override def decrypt(raw: Array[Byte]): F[Either[Unit, Array[Byte]]] = raw.asRight.pure

  def make[F[_]: Async: Logger](alg: CryptoAlg): F[Either[Unit, CypherService[F]]] = {
    alg match
      case CryptoAlg.Gpg(fg) => GpgImpl[F](fg).asRight.pure[F]
      case CryptoAlg.Raw     => CypherService.empty.asRight.pure[F]
  }

@main
def main(): Unit = {

  val fg = "C7FE51E3A3790ABE0D9FD172DA874AB03CF06294"

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
