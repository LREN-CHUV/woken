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
import akka.http.scaladsl.server.Directives._
import cats.effect._
import cats.implicits._
import ch.chuv.lren.woken.akka.{ AkkaServer, CoreSystem }
import ch.chuv.lren.woken.api._
import ch.chuv.lren.woken.api.ssl.WokenSSLConfiguration
import ch.chuv.lren.woken.backends.HttpClient.checkHealth
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{ Failure, Success }

class WebServer[F[_]: ConcurrentEffect: ContextShift: Timer](
    override val core: CoreSystem,
    override val config: WokenConfiguration,
    val databaseServices: DatabaseServices[F],
    val backendServices: BackendServices[F]
) extends Api
    with StaticResources
    with WokenSSLConfiguration {

  import WebServer.logger

  val binding: Future[Http.ServerBinding] = {
    import core._
    val http = Http()
    val app  = config.app

    if (app.webServicesHttps) http.setDefaultServerHttpContext(https)

    logger.info(
      s"Start Web server on http${if (app.webServicesHttps) "s" else ""}://${app.networkInterface}:${app.webServicesPort}"
    )

    // Start a new HTTP server on port 8080 with our service actor as the handler
    http.bindAndHandle(
      routes(databaseServices, backendServices) ~ staticResources,
      interface = app.networkInterface,
      port = app.webServicesPort
    )
  }

  def selfChecks(): Boolean = {
    logger.info("Self checks...")

    val b = Await.result(binding, 30.seconds)

    val url =
      s"http${if (config.app.webServicesHttps) "s" else ""}://${config.app.networkInterface}:${config.app.webServicesPort}/cluster/ready"
    val endpointCheck = checkHealth(url)
    val result: F[Boolean] = endpointCheck.check.flatMap { check =>
      check.value.isHealthy.pure[F]
    }
    val checkResult: Boolean = Effect[F].toIO(result).unsafeRunSync()
    if (checkResult) {
      logger.info(s"[OK] Web server is running on ${b.localAddress}")
      true
    } else {
      logger.warn(s"[FAIL] Web server is not running on ${b.localAddress}")
      false
    }
  }

  def unbind(): F[Unit] = {
    import core._

    Sync[F].defer {
      logger.warn("Stopping here", new Exception(""))

      logger.info(s"Shutdown Web server")

      // Attempt to leave the cluster before shutting down
      val serverShutdown = binding
        .flatMap(_.unbind())
      Async[F].async { cb =>
        serverShutdown.onComplete {
          case Success(_)     => cb(Right(()))
          case Failure(error) => cb(Left(error))
        }
      }
    }
  }
}

object WebServer {

  private val logger: Logger = Logger("woken.WebServer")

  /** Resource that creates and yields a web server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F],
      config: WokenConfiguration
  ): Resource[F, WebServer[F]] =
    // start a new HTTP server with our service actor as the handler
    Resource.make(Sync[F].defer[WebServer[F]] {
      logger.info(s"Start web server on port ${config.app.webServicesPort}")
      val server =
        new WebServer(akkaServer, config, akkaServer.databaseServices, akkaServer.backendServices)

      if (server.selfChecks())
        Sync[F].delay(server)
      else
        Sync[F].raiseError(new Exception("Server failed to start: self-checks did not pass"))
    })(_.unbind())

}
