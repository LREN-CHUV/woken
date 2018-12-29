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
import akka.http.scaladsl.server.{ Directives, Route, StandardRoute }
import akka.http.scaladsl.model.StatusCodes._
import akka.management.cluster.{ ClusterHealthCheck, ClusterHttpManagementRoutes }
import akka.management.http.ManagementRouteProviderSettings
import cats.{ Id, Monad, Reducible, Semigroup }
import cats.data.NonEmptyList
import cats.implicits._
import cats.effect.{ Effect, IO }
import ch.chuv.lren.woken.config.AppConfiguration
import ch.chuv.lren.woken.service.DatabaseServices
import sup.{ Health, HealthCheck, HealthReporter }

class MonitoringWebService[F[_]: Effect](cluster: Cluster,
                                         appConfig: AppConfiguration,
                                         databaseServices: DatabaseServices[F])
    extends Directives {

  val healthRoute: F[Route] = pathPrefix("health") {
    get {
      // TODO: proper health check is required, check db connection, check cluster availability...
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else if (!appConfig.disableWorkers && cluster.state.members.size < 2)
        complete("UP - Expected at least one worker (Woken validation server) in the cluster")
      else
        complete("UP")
    }
  }.pure[F]

  val readinessRoute: F[Route] = pathPrefix("readiness") {
    get {
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else
        complete("READY")
    }
  }.pure[F]

  val clusterRoutes: F[Route] = ClusterHttpManagementRoutes(cluster).pure[F]

  val healthCheckRoutes = pathPrefix("cluster") {
    new ClusterHealthCheck(cluster.system).routes(new ManagementRouteProviderSettings {
      override def selfBaseUri: Uri = Uri./
    })
  }.pure[F]

  val dbHealth: F[Route] = {
    val featuresCheck: HealthCheck[F, Id] = databaseServices.featuresService.healthCheck
    val jobsCheck                         = databaseServices.jobResultService.healthCheck
    val variablesCheck                    = databaseServices.variablesMetaService.healthCheck

    val reporter: HealthReporter[F, NonEmptyList, Id] =
      HealthReporter.fromChecks(featuresCheck, jobsCheck, variablesCheck)

    dbHealthCheckResponse(reporter)
  }

  val routes: F[Route] = for {
    hr  <- healthRoute
    rr  <- readinessRoute
    cr  <- clusterRoutes
    hcr <- healthCheckRoutes
    dbh <- dbHealth
  } yield hr ~ rr ~ cr ~ hcr ~ dbh

  private def dbHealthCheckResponse[H[_]: Reducible](
      healthCheck: HealthCheck[F, H]
  )(implicit combineHealthChecks: Semigroup[Health]) =
    healthCheck.check.flatMap { check =>
      if (check.value.reduce.isHealthy) {
        pathPrefix("db") {
          get {
            complete(OK)
          }
        }.pure[F]
      } else {
        pathPrefix("db") {
          get {
            complete(ServiceUnavailable)
          }

        }
      }.pure[F]
    }

}
