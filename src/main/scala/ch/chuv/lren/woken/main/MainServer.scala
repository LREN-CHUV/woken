package ch.chuv.lren.woken.main

import cats.effect._
import ch.chuv.lren.woken.akka.AkkaServer
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.monitoring.KamonMonitoring
import ch.chuv.lren.woken.service.DatabaseServices
import ch.chuv.lren.woken.web.WebServer

import scala.language.higherKinds

object MainServer {

  /** A single-element stream that starts the server up and shuts it down on exit. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      cfg: WokenConfiguration
  )(implicit ev: ContextShift[IO]): Resource[F, Unit] = {

    val databaseService = DatabaseServices.resource[F](cfg)
    val akkaServer      = AkkaServer.resource[F](databaseService, cfg)

    // TODO: backendServer should ensure connection to Chronos
    for {
      _ <- KamonMonitoring.resource[F](akkaServer, cfg)
      _ <- WebServer.resource[F](akkaServer, cfg)
    } yield ()

  }
}
