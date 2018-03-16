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
import akka.pattern.ask
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import ch.chuv.lren.woken.api.swagger.MiningServiceApi
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.AlgorithmLibraryService
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import kamon.akka.http.TracingDirectives
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object MiningWebService

// this trait defines our service behavior independently from the service actor
class MiningWebService(
    val masterRouter: ActorRef,
    override val appConfiguration: AppConfiguration,
    val jobsConf: JobsConfiguration
)(implicit system: ActorSystem)
    extends MiningServiceApi
    with FailureHandling
    with DefaultJsonProtocol
    with SprayJsonSupport
    with PredefinedToResponseMarshallers
    with SecuredRouteHelper
    with WebsocketSupport
    with TracingDirectives
    with LazyLogging {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = runMiningJob ~ runExperiment ~ listAlgorithms

  import spray.json._
  import queryProtocol._

  override def listAlgorithms: Route =
    securePathWithWebSocket(
      "mining" / ("algorithms" | "methods"),
      listAlgorithmsFlow,
      get {
        operationName("listAlgorithms", Map("requestType" -> "http-get")) {
          complete(AlgorithmLibraryService().algorithms())
        }
      }
    )

  override def runMiningJob: Route =
    securePathWithWebSocket(
      "mining" / "job",
      miningFlow,
      post {
        operationName("mining", Map("requestType" -> "http-post")) {
          entity(as[MiningQuery]) { query: MiningQuery => ctx =>
            ctx.complete {
              (masterRouter ? query)
                .mapTo[QueryResult]
                .map {
                  case qr if qr.error.nonEmpty => BadRequest -> qr.toJson
                  case qr if qr.data.nonEmpty  => OK         -> qr.toJson
                }
                .recoverWith {
                  case e =>
                    logger.warn(s"Query $query failed with error $e")
                    Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
                }
            }
          }
        }
      }
    )

  override def runExperiment: Route =
    securePathWithWebSocket(
      "mining" / "experiment",
      experimentFlow,
      post {
        operationName("experiment", Map("requestType" -> "http-post")) {
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
                    logger.warn(s"Query $query failed with error $e")
                    Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
                }
            }
          }
        }
      }
    )
}
