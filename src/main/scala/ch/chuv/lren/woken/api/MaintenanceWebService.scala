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

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cats.effect.Effect
import ch.chuv.lren.woken.api.swagger.MaintenanceServiceApi
import ch.chuv.lren.woken.config.AppConfiguration
import ch.chuv.lren.woken.service.MiningCacheService
import com.typesafe.scalalogging.LazyLogging
import kamon.akka.http.TracingDirectives
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.language.higherKinds

// this trait defines our service behavior independently from the service actor
class MaintenanceWebService[F[_]: Effect](
    val miningCacheService: MiningCacheService[F],
    override val appConfiguration: AppConfiguration
)(implicit val system: ActorSystem, implicit val materializer: ActorMaterializer)
    extends MaintenanceServiceApi
    with FailureHandling
    with DefaultJsonProtocol
    with SprayJsonSupport
    with PredefinedToResponseMarshallers
    with SecuredRouteHelper
    with TracingDirectives
    with LazyLogging {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = prefillMiningCache ~ maintainMiningCache ~ resetMiningCache

  override def prefillMiningCache: Route = securePath(
    "maintenance" / "miningCache" / "prefill",
    post {
      operationName("prefill", Map("requestType" -> "http-post")) {
        miningCacheService.prefill()
        complete("OK")
      }
    }
  )
  override def resetMiningCache: Route = securePath(
    "maintenance" / "miningCache" / "reset",
    post {
      operationName("reset", Map("requestType" -> "http-post")) {
        miningCacheService.resetCache()
        complete("OK")
      }
    }
  )

  override def maintainMiningCache: Route = securePath(
    "maintenance" / "miningCache" / "maintain",
    post {
      operationName("clean", Map("requestType" -> "http-post")) {
        miningCacheService.maintainCache()
        complete("OK")
      }
    }
  )
}
