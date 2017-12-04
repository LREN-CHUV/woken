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

import akka.actor.ActorSystem
import com.typesafe.config.Config
import eu.hbp.mip.woken.config.{ JobsConfiguration, MetaDatabaseConfig, WokenConfig }
import spray.routing.{ HttpService, Route }
import eu.hbp.mip.woken.core.{ Core, CoreActors }
import eu.hbp.mip.woken.dao.{ FeaturesDAL, JobResultsDAL }

/**
  * The REST API layer. It exposes the REST services, but does not provide any
  * web server interface.<br/>
  * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
  * to the top-level actors that make up the system.
  */
trait Api extends HttpService with CoreActors with Core {

  protected implicit val system: ActorSystem

  def config: Config
  def featuresDAL: FeaturesDAL
  def resultsDAL: JobResultsDAL
  // TODO: replace by MetaDAL
  def metaDbConfig: MetaDatabaseConfig

  private lazy val jobsConf = JobsConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  // TODO: refactor
  private lazy val defaults = WokenConfig.defaultSettings
  lazy val mining_service =
    new MiningService(chronosHttp,
                      featuresDAL,
                      resultsDAL,
                      metaDbConfig,
                      jobsConf,
                      defaults.mainTable)

  val routes: Route = new SwaggerService().routes ~
    mining_service.routes

}
