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
import ch.chuv.lren.woken.authentication.BasicAuthenticator
import ch.chuv.lren.woken.config.AppConfiguration
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse }
import kamon.akka.http.TracingDirectives

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class MetadataApiService(
    val masterRouter: ActorRef,
    override val appConfiguration: AppConfiguration
)(implicit system: ActorSystem)
    extends MetadataServiceApi
    with SprayJsonSupport
    with BasicAuthenticator
    with TracingDirectives {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = listDatasets

  import spray.json._
  import ch.chuv.lren.woken.messages.datasets.datasetsProtocol._

  override def listDatasets: Route = path("datasets") {
    get {
      operationName("mining", Map("requestType" -> "http-post")) { ctx =>
        ctx.complete {
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
    }
  }
}
