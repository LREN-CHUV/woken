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

import javax.ws.rs.{ GET, Path }
import akka.http.scaladsl.server.{ Directives, Route }
import ch.chuv.lren.woken.messages.datasets.DatasetsResponse
import ch.chuv.lren.woken.messages.variables.VariablesForDatasetsResponse
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.{ Operation, Parameter }
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
import io.swagger.v3.oas.annotations.responses.ApiResponse

@Path("/metadata")
trait MetadataServiceApi extends Directives {

  @Path("/datasets")
  @Operation(
    summary = "Get list of datasets",
    description = "Get catalog containing available datasets",
    tags = Array("api"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - list of datasets",
        content = Array(
          new Content(mediaType = "application/json",
                      schema = new Schema(implementation = classOf[DatasetsResponse]))
        )
      ),
      new ApiResponse(responseCode = "401",
                      description = "Authentication required",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "403",
                      description = "Authentication failed",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "500",
                      description = "Internal server error",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def listDatasets: Route

  @Path("/variables")
  @Operation(
    summary = "Get variables metadata for all available datasets",
    description = "Get list of variable metadata for all available datasets",
    tags = Array("api"),
    parameters = Array(
      new Parameter(
        name = "datasets",
        description = "comma separated list of datasets IDs where variables should be fetched from",
        in = ParameterIn.QUERY,
        schema = new Schema(implementation = classOf[String], required = false)
      ),
      new Parameter(
        name = "exhaustive",
        description = "if set to true variables returned should be present in all datasets",
        in = ParameterIn.QUERY,
        schema = new Schema(implementation = classOf[Boolean], required = false)
      )
    ),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - list of variables",
        content = Array(
          new Content(mediaType = "application/json",
                      schema = new Schema(implementation = classOf[VariablesForDatasetsResponse]))
        )
      ),
      new ApiResponse(responseCode = "401",
                      description = "Authentication required",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "403",
                      description = "Authentication failed",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "500",
                      description = "Internal server error",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def listVariables: Route
}
