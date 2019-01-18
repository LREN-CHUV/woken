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

import akka.cluster.Cluster
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.model.StatusCodes._
import akka.management.cluster.{ ClusterHealthCheck, ClusterHttpManagementRoutes }
import akka.management.http.ManagementRouteProviderSettings
import cats.implicits._
import cats.effect.Effect
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.core.fp.runNow
import sup.data.Report._
import sup.data.{ HealthReporter, Tagged }

import scala.language.higherKinds

class MonitoringWebService[F[_]: Effect](cluster: Cluster,
                                         appConfig: AppConfiguration,
                                         jobsConfig: JobsConfiguration,
                                         databaseServices: DatabaseServices[F],
                                         backendServices: BackendServices[F])
    extends Directives {

  private val allChecks =
    HealthReporter.fromChecks(databaseServices.healthChecks, backendServices.healthChecks)

  val healthRoute: Route = pathPrefix("health") {
    get {
      val up = runNow(allChecks.check).value.health.isHealthy
      (cluster.state.leader.nonEmpty, !appConfig.disableWorkers, cluster.state.members.size < 2, up) match {
        case (true, true, true, true) =>
          complete("UP - Expected at least one worker (Woken validation server) in the cluster")
        case (true, _, _, true) => complete("UP")
        case (false, _, _, _) =>
          complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
        case (_, _, _, false) =>
          complete(
            (StatusCodes.InternalServerError,
             runNow(databaseServices.healthChecks.check.map(_.value.show)))
          )
      }
    }
  }

  val readinessRoute: Route = pathPrefix("readiness") {
    get {
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else
        complete("READY")
    }
  }

  val clusterRoutes: Route = ClusterHttpManagementRoutes(cluster)

  val healthCheckRoutes: Route = pathPrefix("cluster") {
    new ClusterHealthCheck(cluster.system).routes(new ManagementRouteProviderSettings {
      override def selfBaseUri: Uri = Uri./
    })
  }

  val dbHealth: Route = pathPrefix("db") {
    get {
      if (runNow(databaseServices.healthChecks.check).value.health.isHealthy) {
        complete(OK)
      } else {
        complete(
          (StatusCodes.InternalServerError,
           runNow(databaseServices.healthChecks.check.map(_.value.show)))
        )
      }
    }
  }

  val routes: Route = healthRoute ~ readinessRoute ~ clusterRoutes ~ healthCheckRoutes ~ dbHealth

  type TaggedS[H] = Tagged[String, H]

}
