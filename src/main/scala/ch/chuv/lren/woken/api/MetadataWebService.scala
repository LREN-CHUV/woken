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

import akka.http.scaladsl.server.Route
import akka.actor._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.util.Timeout
import akka.pattern.ask
import ch.chuv.lren.woken.api.swagger.MetadataServiceApi
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.datasets.{ DatasetId, DatasetsQuery, DatasetsResponse }
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class MetadataApiService(
    val masterRouter: ActorRef,
    val featuresDatabase: FeaturesDAL,
    override val appConfiguration: AppConfiguration,
    val jobsConf: JobsConfiguration
)(implicit system: ActorSystem)
    extends MetadataServiceApi
    with SprayJsonSupport
    with RouteHelpers
    with WebsocketSupport
    with LazyLogging {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = listDatasets ~ listVariables

  import spray.json._
  import ch.chuv.lren.woken.messages.variables.variablesProtocol._

  override def listDatasets: Route =
    securePathWithWebSocket(
      "datasets",
      listDatasetsFlow,
      get {
        complete {
          (masterRouter ? DatasetsQuery)
            .mapTo[DatasetsResponse]
            .map { datasetResponse =>
              OK -> datasetResponse.datasets.toJson
            }
            .recoverWith {
              case e => Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
            }
        }
      }
    )

  override def listVariables: Route =
    securePathWithWebSocket(
      "variables",
      listVariableMetadataFlow,
      get {
        parameters('datasets.?) { datasets =>
          println(s"datasets: $datasets")
          complete {
            (masterRouter ? VariablesForDatasetsQuery(datasets = Set(), includeNulls = true))
              .mapTo[VariablesForDatasetsResponse]
              .map {
                case variablesResponse if variablesResponse.error.nonEmpty =>
                  BadRequest -> variablesResponse.toJson
                case variablesResponse => OK -> variablesResponse.toJson
              }
              .recoverWith {
                case e => Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
              }
          }
        }
      }
    )
}
