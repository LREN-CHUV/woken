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

import ch.chuv.lren.woken.api.swagger.SwaggerService
import ch.chuv.lren.woken.config.AppConfiguration
import ch.chuv.lren.woken.core.{ CoordinatorConfig, Core, CoreActors }
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.service.{ JobResultService, VariablesMetaService }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

/**
  * The REST API layer. It exposes the REST services, but does not provide any
  * web server interface.<br/>
  * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
  * to the top-level actors that make up the system.
  */
trait Api extends CoreActors with Core {

  val featuresDAL: FeaturesDAL
  val jobResultService: JobResultService
  val variablesMetaService: VariablesMetaService
  val appConfig: AppConfiguration
  val coordinatorConfig: CoordinatorConfig

  lazy val miningService =
    new MiningService(
      mainRouter,
      featuresDAL,
      appConfig,
      jobsConf
    )

  lazy val metadataService =
    new MetadataApiService(mainRouter, appConfig)

  val routes: Route = SwaggerService.routes ~ miningService.routes ~ metadataService.routes ~
    pathPrefix("health") {
      get {
        // TODO: proper health check is required, check db connection, check cluster availability...
        complete("OK")
      }
    }

}

trait RestMessage
