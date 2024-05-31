package alpasso.daeamon.gpg

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.unsafe.implicits.global
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*

import java.security.Security
import scala.util.*

@main
def main =
  Security.addProvider(new BouncyCastleProvider())

  val keyId = "64695F7D212F979D3553AFC5E0D6CE10FBEB0423"
  val pass  = "$!lentium"

  val gpgShell = GpgShell.make[IO]

  val res = for
    pk      <- EitherT(GpgShell.make[IO].loadKeyPair(keyId, pass))
    gpg     <- EitherT.pure(GpgService.make[IO](pk))
    data    <- EitherT(gpg.encrypt("message".getBytes))
    _       <- EitherT.liftF(IO.println(s"data: ${data.mkString}"))
    origin  <- EitherT(gpg.decrypt(data))
    _       <- EitherT.liftF(IO.println(s"Origin: ${new String(origin)}"))
  yield ()

  res.value.unsafeRunSync()
