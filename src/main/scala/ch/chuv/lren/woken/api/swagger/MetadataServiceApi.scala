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

package ch.chuv.lren.woken.api.swagger

import javax.ws.rs.Path

import akka.http.scaladsl.server.{ Directives, Route }
import ch.chuv.lren.woken.messages.datasets.DatasetsResponse
import io.swagger.annotations._

@Api(value = "/", consumes = "application/json", produces = "application/json")
trait MetadataServiceApi extends Directives {
  @Path("/datasets")
  @ApiOperation(
    value = "Get dataset catalog",
    notes = "Get catalog containing available datasets",
    httpMethod = "GET",
    consumes = "application/json",
    response = classOf[DatasetsResponse]
  )
  @ApiImplicitParams(Array())
  @ApiResponses(
    Array(
      new ApiResponse(code = 200,
                      message = "Dataset listing",
                      response = classOf[spray.json.JsObject]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def listDatasets: Route
}
