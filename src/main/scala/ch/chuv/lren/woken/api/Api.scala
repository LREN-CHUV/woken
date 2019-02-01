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

package ch.chuv.lren.woken.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.effect.{ ConcurrentEffect, ContextShift, Timer }
import ch.chuv.lren.woken.akka.CoreSystem
import ch.chuv.lren.woken.api.swagger.SwaggerService
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices, MiningCacheService }
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors

import scala.language.higherKinds

/**
  * The REST API layer. It exposes the REST services, but does not provide any
  * web server interface.<br/>
  * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
  * to the top-level actors that make up the system.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
trait Api {

  def core: CoreSystem
  def config: WokenConfiguration

  def routes[F[_]: ConcurrentEffect: ContextShift: Timer](
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F]
  ): Route = {
    implicit val system: ActorSystem             = core.system
    implicit val materializer: ActorMaterializer = core.actorMaterializer

    val miningService =
      new MiningWebService(
        core.mainRouter,
        config.app,
        config.jobs
      )

    val metadataService =
      new MetadataWebService(
        core.mainRouter,
        config.app,
        config.jobs
      )

    val monitoringService =
      new MonitoringWebService(core.cluster,
                               config.config,
                               config.app,
                               config.jobs,
                               databaseServices,
                               backendServices)

    val miningCacheService = MiningCacheService(core.mainRouter, databaseServices)

    val maintenanceService =
      new MaintenanceWebService[F](miningCacheService, config.app)

    cors()(
      SwaggerService.routes ~ miningService.routes ~ metadataService.routes ~ monitoringService.routes ~ maintenanceService.routes
    )

  }

}
