/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.web

import akka.http.scaladsl.Http
import cats.effect._
import ch.chuv.lren.woken.akka.AkkaServer
import ch.chuv.lren.woken.api.Api
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.Core
import ch.chuv.lren.woken.api.ssl.WokenSSLConfiguration
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import kamon.system.SystemMetrics

import scala.concurrent.{ Await, Future }
import scala.sys.ShutdownHookThread
import scala.concurrent.duration._
import scala.language.higherKinds

class WebServer[F[_]: ConcurrentEffect: Timer](override val core: Core,
                                               override val config: WokenConfiguration)
    extends Api
    with StaticResources
    with WokenSSLConfiguration {

  val binding: Future[Http.ServerBinding] = {
    import core._
    val http = Http()
    if (config.app.webServicesHttps) http.setDefaultServerHttpContext(https)

    // Start a new HTTP server on port 8080 with our service actor as the handler
    val binding = http.bindAndHandle(
      routes,
      interface = config.app.networkInterface,
      port = config.app.webServicesPort
    )

    // Ensure that the constructed ActorSystem is shut down when the JVM shuts down
    val _: ShutdownHookThread = sys.addShutdownHook {
      unbind()
    }

    binding
  }

  def unbind(): F[Unit] = {
    import core._

    // Attempt to leave the cluster before shutting down
    val serverShutdown = binding
      .flatMap(_.unbind())
      .flatMap(_ => system.terminate())

    serverShutdown.onComplete(_ => ())

    Sync[F].delay(Await.result(serverShutdown, 5.seconds))
  }
}

/*{

  def startServices(): Unit = {
    logger.info(s"Start web server on port ${config.app.webServicesPort}")

    implicit val timeout: Timeout = Timeout(5.seconds)
    import core._

    if (config.app.webServicesHttps) Http().setDefaultServerHttpContext(https)

    // start a new HTTP server on port 8080 with our service actor as the handler
    val binding: Future[Http.ServerBinding] = Http().bindAndHandle(
      routes,
      interface = config.app.networkInterface,
      port = config.app.webServicesPort
    )

    system.registerOnTermination {
      cluster.leave(cluster.selfAddress)
      Kamon.stopAllReporters()
      SystemMetrics.stopCollecting()
    }

    /**
 * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
 */
    val _: ShutdownHookThread = sys.addShutdownHook {
      // Attempt to leave the cluster before shutting down
      val serverShutdown = binding
        .flatMap(_.unbind())
        .flatMap(_ => system.terminate())

      serverShutdown.onComplete(_ => ())

      val _ = Await.result(serverShutdown, 5.seconds)

    }

  }
}
 */

object WebServer extends LazyLogging {

  /** Resource that creates and yields a web server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServerResource: Resource[F, AkkaServer[F]],
      config: WokenConfiguration
  ): Resource[F, WebServer[F]] = {

    logger.info(s"Start web server on port ${config.app.webServicesPort}")

    akkaServerResource.flatMap { akkaServer =>
      // start a new HTTP server with our service actor as the handler
      Resource.make(Sync[F].delay(new WebServer(akkaServer, config)))(_.unbind())
    }

  }
}
