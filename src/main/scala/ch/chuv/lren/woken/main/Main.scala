package ch.chuv.lren.woken.main

import cats.effect.{ExitCode, IO, IOApp}
import ch.chuv.lren.woken.config.WokenConfiguration
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

/**
  * Provides the web server (spray-can) for the REST api in ``Api``, using the actor system
  * defined in ``Core``.
  *
  * You may sometimes wish to construct separate ``ActorSystem`` for the web server machinery.
  * However, for this simple application, we shall use the same ``ActorSystem`` for the
  * entire application.
  *
  * Benefits of separate ``ActorSystem`` include the ability to use completely different
  * configuration, especially when it comes to the threading core.model.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
object Main extends IOApp {

  protected lazy val logger: Logger =
    Logger(LoggerFactory.getLogger("Woken"))

  def run(args: List[String]): IO[ExitCode] = {
    val config = WokenConfiguration().unsafeRunSync()
    MainServer.resource[IO](config).use { _ =>
      for {
        _ <- IO(Console.println("Woken startup complete.")) // scalastyle:off
        _ <- IO(Console.println("Press a key to exit.")) // scalastyle:off
        _ <- IO(scala.io.StdIn.readLine())
      } yield ExitCode.Success
    }
  }

}
