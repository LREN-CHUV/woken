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

import javax.ws.rs.{ GET, POST, Path }
import akka.http.scaladsl.server.{ Directives, Route }
import ch.chuv.lren.woken.messages.query._
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse

// This trait documents the API, tries not to pollute the code with annotations

/**
  * Operations for data mining
  */
@Path("/mining")
trait MiningServiceApi extends Directives {
  import queryProtocol._

  @Path("/job")
  @Operation(
    summary = "Run a data mining job",
    description = "Run a data mining job for a single algorithm",
    requestBody = new RequestBody(
      description = "Data mining query to execute",
      content = Array(
        new Content(mediaType = "application/json",
                    schema = new Schema(implementation = classOf[MiningQuery]))
      ),
      required = true
    ),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - Result of the data mining query",
        content = Array(
          new Content(mediaType = "application/json",
                      schema = new Schema(implementation = classOf[QueryResult]))
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
  @POST
  def runMiningJob: Route


  @Path("/experiment")
  @Operation(
    summary = "Run a data mining experiment",
    description = "Run a data mining experiment and return id",
    requestBody = new RequestBody(
      description = "Experiment to execute",
      content = Array(
        new Content(mediaType = "application/json",
                    schema = new Schema(implementation = classOf[ExperimentQuery]))
      ),
      required = true
    ),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - Result of the experiment query",
        content = Array(
          new Content(mediaType = "application/json",
                      schema = new Schema(implementation = classOf[QueryResult]))
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
  def runExperiment: Route

  @Path("/algorithms")
  @Operation(
    summary = "Get complete catalog of mining methods (algorithms)",
    description = "Get catalog containing available mining methods (algorithms)",
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - returns the list of algorithms",
        content = Array(
          new Content(mediaType = "application/json",
                      schema = new Schema(implementation = classOf[MethodsResponse]))
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
  def listAlgorithms: Route

}
