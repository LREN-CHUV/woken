/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import eu.hbp.mip.woken.api.swagger.SwaggerService
import eu.hbp.mip.woken.config.AppConfiguration
import eu.hbp.mip.woken.core.{ CoordinatorConfig, Core, CoreActors }
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.{ JobResultService, VariablesMetaService }
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

  val routes: Route = SwaggerService.routes ~ miningService.routes
  pathPrefix("health") {
    get {
      complete("OK")
    }
  }

}

trait RestMessage
