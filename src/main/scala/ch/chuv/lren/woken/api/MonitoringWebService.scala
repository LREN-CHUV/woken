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
import akka.cluster.{ Cluster, MemberStatus }
import akka.http.scaladsl.model.{ StatusCode, StatusCodes, Uri }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.model.StatusCodes._
import akka.management.cluster.{ ClusterHealthCheck, ClusterHttpManagementRoutes }
import akka.management.http.ManagementRouteProviderSettings
import akka.util.Helpers
import cats.implicits._
import cats.effect.Effect
import ch.chuv.lren.woken.api.swagger.MonitoringServiceApi
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.core.fp.runLater
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import sup.data.Report._
import sup.data.{ HealthReporter, Tagged }

import scala.language.higherKinds
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

/**
  *  Monitoring API
  *
  *  /readiness : readiness check of the application
  *  /health : application health
  *  /health/backend : backend health
  *  /health/db : database health
  *  /health/cluster : cluster health
  *  /cluster/alive : ping works while the cluster seems alive
  *  /cluster/ready : readiness check of the cluster
  *  /cluster/members : list members of the cluster
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"))
class MonitoringWebService[F[_]: Effect](cluster: Cluster,
                                         config: Config,
                                         appConfig: AppConfiguration,
                                         jobsConfig: JobsConfiguration,
                                         databaseServices: DatabaseServices[F],
                                         backendServices: BackendServices[F])(
    implicit val system: ActorSystem
) extends MonitoringServiceApi
    with Directives
    with LazyLogging {

  private val allChecks =
    HealthReporter.fromChecks(databaseServices.healthChecks, backendServices.healthChecks)

  implicit val executionContext: ExecutionContext = system.dispatcher

  def health: Route = pathPrefix("health") {
    pathEndOrSingleSlash {
      get {
        onComplete(delayedHealthChecks) {
          case Success(r) => complete(r)
          case Failure(ex) =>
            complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
        }
      }
    } ~ dbHealth ~ backendHealth ~ clusterHealth
  }

  private def delayedHealthChecks: Future[(StatusCode, String)] =
    runLater(allChecks.check).flatMap { healthResult =>
      val up = healthResult.value.health.isHealthy
      (cluster.state.leader.nonEmpty, !appConfig.disableWorkers, cluster.state.members.size < 2, up) match {
        case (true, true, true, true) =>
          (StatusCodes.OK,
           "UP - Expected at least one worker (Woken validation server) in the cluster")
            .pure[Future]
        case (true, _, _, true) => (StatusCodes.OK, "UP").pure[Future]
        case (false, _, _, _) =>
          val msg = "No leader elected for the cluster"
          logger.warn(msg)
          (StatusCodes.InternalServerError, msg).pure[Future]
        case (_, _, _, false) =>
          runLater(allChecks.check.map(_.value)).map { report =>
            val msg =
              s"${report.health}: \n${report.checks.toList.filter(!_.health.isHealthy).mkString("\n")}"
            logger.warn(msg)
            (StatusCodes.InternalServerError, msg)
          }
      }
    }

  def readiness: Route = pathPrefix("readiness") {
    get {
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else
        complete("READY")
    }
  }

  val clusterManagementRoutes: Route = ClusterHttpManagementRoutes(cluster)

  val clusterHealthRoutes: Route = pathPrefix("cluster") {
    new ClusterHealthCheck(cluster.system).routes(new ManagementRouteProviderSettings {
      override def selfBaseUri: Uri = Uri./
    })
  }

  private val healthcheckConfig = config.getConfig("akka.management.cluster.http.healthcheck")
  private val readyStates: Set[MemberStatus] =
    healthcheckConfig.getStringList("ready-states").asScala.map(memberStatus).toSet

  def clusterHealth: Route = pathPrefix("cluster") {
    get {
      val selfState = cluster.selfMember.status
      if (readyStates.contains(selfState)) complete(StatusCodes.OK)
      else complete(StatusCodes.InternalServerError)
    }
  }

  def backendHealth: Route = pathPrefix("backend") {
    get {
      onComplete(runLater(backendServices.healthChecks.check)) {
        case Success(checks) =>
          if (checks.value.health.isHealthy)
            complete(OK)
          else
            complete((StatusCodes.InternalServerError, checks.value.show))
        case Failure(ex) =>
          complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
      }
    }
  }

  def dbHealth: Route = pathPrefix("db") {
    get {
      onComplete(runLater(databaseServices.healthChecks.check)) {
        case Success(checks) =>
          if (checks.value.health.isHealthy)
            complete(OK)
          else
            complete((StatusCodes.InternalServerError, checks.value.show))
        case Failure(ex) =>
          complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
      }
    }
  }

  val routes: Route = health ~ readiness ~ clusterManagementRoutes ~ clusterHealthRoutes

  type TaggedS[H] = Tagged[String, H]

  private def memberStatus(status: String): MemberStatus =
    Helpers.toRootLowerCase(status) match {
      case "weaklyup" => MemberStatus.WeaklyUp
      case "up"       => MemberStatus.Up
      case "exiting"  => MemberStatus.Exiting
      case "down"     => MemberStatus.Down
      case "joining"  => MemberStatus.Joining
      case "leaving"  => MemberStatus.Leaving
      case "removed"  => MemberStatus.Removed
      case invalid =>
        throw new IllegalArgumentException(
          s"'$invalid' is not a valid MemberStatus. See reference.conf for valid values"
        )
    }

}
