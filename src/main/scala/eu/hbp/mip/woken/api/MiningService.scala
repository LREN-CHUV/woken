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

import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.authentication.BasicAuthentication
import eu.hbp.mip.woken.config.{ DatabaseConfiguration, JobsConfiguration, WokenConfig }
import spray.http.MediaTypes._
import spray.http._
import spray.routing.Route
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.core._
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.{ JobResultService, VariablesMetaService }

object MiningService {}

// this trait defines our service behavior independently from the service actor
class MiningService(val chronosService: ActorRef,
                    val featuresDatabase: FeaturesDAL,
                    val jobResultService: JobResultService,
                    val variablesMetaService: VariablesMetaService,
                    val jobsConf: JobsConfiguration,
                    val defaultFeaturesTable: String)(implicit system: ActorSystem)
    extends MiningServiceApi
    with PerRequestCreator
    with DefaultJsonFormats
    with BasicAuthentication {

  override def context: ActorRefFactory = system

  implicit val executionContext = context.dispatcher

  val routes: Route = (_) => () // mining ~ experiment ~ listMethods

  /*
  import ApiJsonSupport._
  import CoordinatorActor._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  // TODO: improve passing configuration around
  private lazy val config = ConfigFactory.load()
  private lazy val coordinatorConfig = CoordinatorConfig(chronosService,
                                                         featuresDatabase,
                                                         resultDatabase,
                                                         WokenConfig.app.dockerBridgeNetwork,
                                                         jobsConf,
                                                         DatabaseConfiguration.factory(config))

  override def listMethods: Route = path("mining" / "list-methods") {
    authenticate(basicAuthenticator) { user =>
      import spray.json._

      get {
        respondWithMediaType(`application/json`) {
          complete(MiningService.methods_mock.parseJson.compactPrint)
        }
      }
    }
  }

  override def mining: Route = path("mining" / "job") {
    authenticate(basicAuthenticator) { user =>
      import FunctionsInOut._

      post {
        entity(as[MiningQuery]) {
          case MiningQuery(variables, covariables, groups, filters, Algorithm(c, n, p))
              if c == "" || c == "data" =>
            ctx =>
              {
                ctx.complete(
                  featuresDatabase.queryData(defaultFeaturesTable, {
                    variables ++ covariables ++ groups
                  }.distinct.map(_.code))
                )
              }

          case query: MiningQuery =>
            val job = miningQuery2job(metaDbConfig)(query)
            miningJob(coordinatorConfig) {
              Start(job)
            }
        }
      }
    }
  }

  override def experiment: Route = path("mining" / "experiment") {
    authenticate(basicAuthenticator) { user =>
      import FunctionsInOut._

      post {
        entity(as[ExperimentQuery]) { query: ExperimentQuery =>
          {
            val job = experimentQuery2job(metaDbConfig)(query)
            experimentJob(coordinatorConfig) {
              ExperimentActor.Start(job)
            }
          }
        }
      }
    }
  }

  private def newCoordinatorActor(coordinatorConfig: CoordinatorConfig): ActorRef =
    context.actorOf(CoordinatorActor.props(coordinatorConfig))

  private def newExperimentActor(coordinatorConfig: CoordinatorConfig): ActorRef =
    context.actorOf(ExperimentActor.props(coordinatorConfig))

  def miningJob(coordinatorConfig: CoordinatorConfig)(message: RestMessage): Route =
    ctx => perRequest(ctx, newCoordinatorActor(coordinatorConfig), message)

  def experimentJob(coordinatorConfig: CoordinatorConfig)(message: RestMessage): Route =
    ctx => perRequest(ctx, newExperimentActor(coordinatorConfig), message)

 */
}
