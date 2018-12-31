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
import akka.cluster.pubsub.DistributedPubSub
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.model.StatusCodes._
import akka.management.cluster.{ ClusterHealthCheck, ClusterHttpManagementRoutes }
import akka.management.http.ManagementRouteProviderSettings
import cats.{ Id, Reducible, Semigroup }
import cats.data.NonEmptyList
import cats.implicits._
import cats.effect.Effect
import ch.chuv.lren.woken.akka.monitoring.DistributedPubSubHealthCheck
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.service.DatabaseServices
import sup.{ Health, HealthCheck, HealthReporter }

class MonitoringWebService[F[_]: Effect](cluster: Cluster,
                                         appConfig: AppConfiguration,
                                         jobsConfig: JobsConfiguration,
                                         databaseServices: DatabaseServices[F])
    extends Directives {

  val healthRoute: Route = pathPrefix("health") {
    get {
      (cluster.state.leader.isEmpty,
       !appConfig.disableWorkers,
       cluster.state.members.size < 2,
       dbChecks,
       wokenValidationCheck,
       chronosServiceCheck) match {
        case (false, true, true, true, true, true) =>
          complete("UP - Expected at least one worker (Woken validation server) in the cluster")
        case (true, _, _, _, _, _) =>
          complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
        case (false, _, _, true, true, true) => complete("UP")
        case (_, _, _, false, _, _) =>
          complete((StatusCodes.InternalServerError, "DB checks failed."))
        case (_, _, _, _, false, _) =>
          complete((StatusCodes.InternalServerError, "Woken validation checks failed."))
        case (false, _, _, _, _, false) =>
          complete((StatusCodes.InternalServerError, "Chronos service checks failed."))
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

  val healthCheckRoutes = pathPrefix("cluster") {
    new ClusterHealthCheck(cluster.system).routes(new ManagementRouteProviderSettings {
      override def selfBaseUri: Uri = Uri./
    })
  }

  val dbHealth: Route = pathPrefix("db") {
    get {
      if (dbChecks) {
        complete(OK)
      } else {
        complete((StatusCodes.InternalServerError, "DBs are not ready."))
      }
    }
  }

  val routes: Route = healthRoute ~ readinessRoute ~ clusterRoutes ~ healthCheckRoutes ~ dbHealth

  private def wokenValidationCheck: Boolean = {
    val mediator = DistributedPubSub(cluster.system).mediator
    val validationServiceCheck: HealthCheck[F, Id] =
      DistributedPubSubHealthCheck.checkValidation(mediator)
    val scoringServiceCheck: HealthCheck[F, Id] =
      DistributedPubSubHealthCheck.checkScoring(mediator)
    val reporter: HealthReporter[F, NonEmptyList, Id] =
      HealthReporter.fromChecks(validationServiceCheck, scoringServiceCheck)

    Effect[F].toIO(healthCheckResponse(reporter)).unsafeRunSync()
  }

  private def chronosServiceCheck: Boolean = {
    val chronosUrl = jobsConfig.chronosServerUrl
    val url        = s"$chronosUrl/v1/scheduler/jobs"

    val chronosCheck: HealthCheck[F, Id] = healthCheck(url)
    val checkResult = chronosCheck.check.flatMap { check =>
      check.value.isHealthy.pure[F]
    }

    Effect[F].toIO(checkResult).unsafeRunSync()
  }

  private def dbChecks: Boolean = {
    val featuresCheck: HealthCheck[F, Id] = databaseServices.featuresService.healthCheck
    val jobsCheck                         = databaseServices.jobResultService.healthCheck
    val variablesCheck                    = databaseServices.variablesMetaService.healthCheck

    val reporter: HealthReporter[F, NonEmptyList, Id] =
      HealthReporter.fromChecks(featuresCheck, jobsCheck, variablesCheck)

    Effect[F].toIO(healthCheckResponse(reporter)).unsafeRunSync()
  }

  private def healthCheckResponse[H[_]: Reducible](
      healthCheck: HealthCheck[F, H]
  )(implicit combineHealthChecks: Semigroup[Health]) =
    healthCheck.check.flatMap { check =>
      check.value.reduce.isHealthy.pure[F]
    }

}
