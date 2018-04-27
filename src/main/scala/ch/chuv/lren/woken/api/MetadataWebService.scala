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

import akka.actor._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers._
import akka.http.scaladsl.model.StatusCodes._
import akka.util.Timeout
import akka.pattern.ask
import ch.chuv.lren.woken.api.swagger.MetadataServiceApi
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.messages.datasets.{
  DatasetId,
  DatasetsProtocol,
  DatasetsQuery,
  DatasetsResponse
}
import ch.chuv.lren.woken.messages.remoting.RemotingProtocol
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse,
  variablesProtocol
}
import com.typesafe.scalalogging.LazyLogging
import kamon.akka.http.TracingDirectives
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class MetadataWebService(
    val masterRouter: ActorRef,
    override val appConfiguration: AppConfiguration,
    val jobsConf: JobsConfiguration
)(implicit system: ActorSystem)
    extends MetadataServiceApi
    with DatasetsProtocol
    with RemotingProtocol
    with DefaultJsonProtocol
    with SprayJsonSupport
    with SecuredRouteHelper
    with WebsocketSupport
    with TracingDirectives
    with LazyLogging {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = listDatasets ~ listVariables

  import spray.json._
  import variablesProtocol._

  override def listDatasets: Route =
    securePathWithWebSocket(
      "metadata" / "datasets",
      listDatasetsFlow,
      get {
        operationName("listDatasets", Map("requestType" -> "http-get")) {
          parameters('table.?) { table =>
            complete {
              (masterRouter ? DatasetsQuery(table))
                .mapTo[DatasetsResponse]
                .map { datasetResponse =>
                  OK -> datasetResponse.datasets.toJson
                }
                .recoverWith {
                  case e =>
                    logger.error(s"Cannot list datasets for table $table", e)
                    Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
                }
            }
          }
        }
      }
    )

  override def listVariables: Route =
    securePathWithWebSocket(
      "metadata" / "variables",
      listVariableMetadataFlow,
      get {
        parameters('datasets.as(CsvSeq[String]).?) {
          datasets =>
            val datasetIds = datasets.map(_.map(DatasetId).toSet).getOrElse(Set())

            complete {
              (masterRouter ? VariablesForDatasetsQuery(datasets = datasetIds, exhaustive = true))
                .mapTo[VariablesForDatasetsResponse]
                .map {
                  case variablesResponse if variablesResponse.error.nonEmpty =>
                    BadRequest -> variablesResponse.toJson
                  case variablesResponse => OK -> variablesResponse.toJson
                }
                .recoverWith {
                  case e =>
                    logger.error(s"Cannot list variables for datasets ${datasets.mkString(",")}", e)
                    Future(BadRequest -> JsObject("error" -> JsString(e.toString)))
                }
            }
        }
      }
    )
}
