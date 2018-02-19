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

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers
import akka.http.scaladsl.model.StatusCode
import akka.pattern.ask
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import ch.chuv.lren.woken.api.swagger.MiningServiceApi
import ch.chuv.lren.woken.authentication.BasicAuthenticator
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.service.AlgorithmLibraryService
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
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
    with BasicAuthenticator
    with WebsocketSupport
    with LazyLogging {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = mining ~ experiment ~ listMethods

  import spray.json._
  import queryProtocol._

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
              case query: MiningQuery
                  if query.algorithm.code == "" || query.algorithm.code == "data" =>
                ctx =>
                  {
                    ctx.complete {
                      val featuresTable = query.targetTable.getOrElse(jobsConf.featuresTable)
                      featuresDatabase.queryData(featuresTable, query.dbAllVars)
                    }
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
                      .recoverWith {
                        case e =>
                          Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
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
                  .recoverWith {
                    case e =>
                      Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
                  }
              }
            }
          }
      }
    }
  }
}
