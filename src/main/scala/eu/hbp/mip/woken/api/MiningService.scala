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

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers
import akka.pattern.ask
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import eu.hbp.mip.woken.api.swagger.MiningServiceApi
import eu.hbp.mip.woken.authentication.BasicAuthentication
import eu.hbp.mip.woken.config.{ AppConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.AlgorithmLibraryService
import akka.util.Timeout
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Failure

object MiningService

// this trait defines our service behavior independently from the service actor
class MiningService(
    val masterRouter: ActorRef,
    val featuresDatabase: FeaturesDAL,
    override val appConfiguration: AppConfiguration,
    val jobsConf: JobsConfiguration
)(implicit system: ActorSystem)
    extends MiningServiceApi
    with FailureHandling
    with DefaultJsonProtocol
    with SprayJsonSupport
    with PredefinedToResponseMarshallers
    with BasicAuthentication
    with WebsocketSupport {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = mining ~ experiment ~ listMethods

  private[this] val logger = LoggerFactory.getLogger(getClass)

  import spray.json._
  import ExternalAPIProtocol._

  override def listMethods: Route = path("mining" / "methods") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { _ =>
      optionalHeaderValueByType[UpgradeToWebSocket](()) {
        case Some(upgrade) =>
          complete(upgrade.handleMessages(listMethodsFlow.watchTermination() { (_, done) =>
            done.onComplete {
              case scala.util.Success(_) =>
                logger.info("WS get methods completed successfully.")
              case Failure(ex) =>
                logger.error(s"WS get methods completed with failure : $ex")
            }
          }))
        case None =>
          get {
            complete(AlgorithmLibraryService().algorithms())
          }
      }
    }
  }

  override def mining: Route = path("mining" / "job") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { user =>
      optionalHeaderValueByType[UpgradeToWebSocket](()) {
        case Some(upgrade) =>
          complete(upgrade.handleMessages(miningFlow.watchTermination() { (_, done) =>
            done.onComplete {
              case scala.util.Success(_) =>
                logger.info("WS mining job completed successfully.")
              case Failure(ex) =>
                logger.error(s"WS mining job completed with failure : $ex")
            }
          }))
        case None =>
          post {
            entity(as[MiningQuery]) {
              case MiningQuery(userId,
                               variables,
                               covariables,
                               groups,
                               filters,
                               datasets,
                               AlgorithmSpec(c, p)) if c == "" || c == "data" =>
                ctx =>
                  {
                    ctx.complete(
                      featuresDatabase.queryData(jobsConf.featuresTable, {
                        variables ++ covariables ++ groups
                      }.distinct.map(_.code))
                    )
                  }

              case query: MiningQuery =>
                ctx =>
                  ctx.complete {
                    (masterRouter ? query)
                      .mapTo[QueryResult]
                      .map {
                        case qr if qr.error.nonEmpty => BadRequest -> qr.toJson
                        case qr if qr.data.nonEmpty  => OK         -> qr.toJson
                      }
                  }
            }
          }
      }
    }
  }

  override def experiment: Route = path("mining" / "experiment") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { user =>
      optionalHeaderValueByType[UpgradeToWebSocket](()) {
        case Some(upgrade) =>
          complete(upgrade.handleMessages(experimentFlow.watchTermination() { (_, done) =>
            done.onComplete {
              case scala.util.Success(_) =>
                logger.info("WS experiment completed successfully.")
              case Failure(ex) =>
                logger.error(s"WS experiment completed with failure : $ex")
            }
          }))
        case None =>
          post {
            entity(as[ExperimentQuery]) { query: ExperimentQuery =>
              complete {
                (masterRouter ? query)
                  .mapTo[QueryResult]
                  .map {
                    case qr if qr.error.nonEmpty => BadRequest -> qr.toJson
                    case qr if qr.data.nonEmpty  => OK         -> qr.toJson
                  }
              }
            }
          }
      }
    }
  }
}
