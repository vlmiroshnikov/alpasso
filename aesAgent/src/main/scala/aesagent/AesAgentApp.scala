package aesagent

import cats.effect.*
import cats.syntax.all.*

import fs2.grpc.syntax.all.*
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder

import java.net.InetSocketAddress

object AesAgentApp extends IOApp:

  private val PORT = 50051

  override def run(args: List[String]): IO[ExitCode] =
    for
      sessionManager <- SessionManager.make[IO]
      service        = new AesAgentServiceImpl[IO](sessionManager)
      server <- NettyServerBuilder
                  .forAddress(new InetSocketAddress("127.0.0.1", PORT))
                  .addService(aesagent.proto.AesAgentServiceFs2Grpc.bindService(service))
                  .build
                  .start[IO]
      _ <- IO.println(s"AES Agent started on port $PORT")
      _ <- IO.println("Press Ctrl+C to stop")
      _ <- server.awaitTermination[IO]
    yield ExitCode.Success
