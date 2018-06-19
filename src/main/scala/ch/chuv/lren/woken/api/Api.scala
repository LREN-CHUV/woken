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

import akka.http.scaladsl.model.StatusCodes
import ch.chuv.lren.woken.api.swagger.SwaggerService
import ch.chuv.lren.woken.core.{ CoordinatorConfig, Core, CoreActors }
import ch.chuv.lren.woken.service.{ FeaturesService, JobResultService }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.typesafe.scalalogging.LazyLogging

/**
  * The REST API layer. It exposes the REST services, but does not provide any
  * web server interface.<br/>
  * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
  * to the top-level actors that make up the system.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
trait Api extends CoreActors with Core with LazyLogging {

  def featuresService: FeaturesService
  def jobResultService: JobResultService
  def coordinatorConfig: CoordinatorConfig

  def routes: Route = {
    val miningService =
      new MiningWebService(
        mainRouter,
        appConfig,
        jobsConfig
      )

    val metadataService =
      new MetadataWebService(
        mainRouter,
        appConfig,
        jobsConfig
      )

    val healthRoute = pathPrefix("health") {
      get {
        // TODO: proper health check is required, check db connection, check cluster availability...
        if (cluster.state.leader.isEmpty)
          complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
        else if (!appConfig.disableWorkers && cluster.state.members.size < 2)
          complete("UP - Expected at least one worker (Woken validation server) in the cluster")
        else
          complete("UP")
      }
    }

    val readinessRoute = pathPrefix("readiness") {
      get {
        if (cluster.state.leader.isEmpty)
          complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
        else
          complete("READY")
      }
    }

    cors()(
      SwaggerService.routes ~ miningService.routes ~ metadataService.routes ~ healthRoute ~ readinessRoute
    )
  }

}

trait RestMessage
