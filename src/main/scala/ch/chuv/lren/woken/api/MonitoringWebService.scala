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
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives.{ complete, get, pathPrefix }
import ch.chuv.lren.woken.config.AppConfiguration

class MonitoringWebService(cluster: Cluster, appConfig: AppConfiguration) {

  val healthRoute: Route = pathPrefix("health") {
    get {
      // TODO: proper health check is required, check db connection, check cluster availability...
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else if (!appConfig.disableWorkers && cluster.state.members.size < 2)
        complete("UP - Expected at least one worker (Woken validation server) in the cluster")
      else
        complete("UP")
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

  val routes: Route = healthRoute ~ readinessRoute

}
