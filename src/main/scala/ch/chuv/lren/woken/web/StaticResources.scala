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

package ch.chuv.lren.woken.web

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._

trait StaticResources {

  def staticResources: Route =
    get {
      path("") {
        pathEndOrSingleSlash {
          getFromResource("swagger-ui/index.html")
        }
      } ~
      pathPrefix("swagger-ui") {
        getFromResourceDirectory("swagger-ui")
      } ~
      pathPrefix("webjars/swagger-ui") {
        getFromResourceDirectory("META-INF/resources/webjars/swagger-ui/3.20.5")
      } ~
      path("favicon.ico") {
        complete(StatusCodes.NotFound)
      } ~
      path(Remaining) { path =>
        getFromResource("root/%s" format path)
      }
    }

}
