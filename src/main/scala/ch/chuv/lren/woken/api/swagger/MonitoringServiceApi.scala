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

import akka.http.scaladsl.server.{ Directives, Route }
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import javax.ws.rs.{ GET, Path }

trait MonitoringServiceApi extends Directives {

  @Path("/health")
  @Operation(
    summary = "Check the health of the application",
    description = "Check the health of the application and the Akka cluster",
    tags = Array("monitoring"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - application is healthy",
        content = Array(
          new Content(mediaType = "text/plain")
        )
      ),
      new ApiResponse(responseCode = "500",
                      description = "Failure - application is not healthy",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def health: Route

  @Path("/health/cluster")
  @Operation(
    summary = "Check the health of the cluster",
    description = "Check the health of the Akka cluster",
    tags = Array("monitoring"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - cluster is healthy",
        content = Array(
          new Content(mediaType = "text/plain")
        )
      ),
      new ApiResponse(responseCode = "500",
                      description = "Failure - cluster is not healthy",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def clusterHealth: Route

  @Path("/health/backend")
  @Operation(
    summary = "Check the health of the backend services for Woken",
    description = "Check the health of the backend services for Woken (Chronos, Woken Worker)",
    tags = Array("monitoring"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - backend is healthy",
        content = Array(
          new Content(mediaType = "text/plain")
        )
      ),
      new ApiResponse(responseCode = "500",
                      description = "Failure - backend is not healthy",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def backendHealth: Route

  @Path("/health/db")
  @Operation(
    summary = "Check the health of the database",
    description = "Check the health of the database",
    tags = Array("monitoring"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - database is healthy",
        content = Array(
          new Content(mediaType = "text/plain")
        )
      ),
      new ApiResponse(responseCode = "500",
                      description = "Failure - database is not healthy",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def dbHealth: Route

  @Path("/readiness")
  @Operation(
    summary = "Check the readiness of the application after startup",
    description = "Check the readiness of the application after startup",
    tags = Array("monitoring"),
    responses = Array(
      new ApiResponse(
        responseCode = "200",
        description = "Success - application is ready",
        content = Array(
          new Content(mediaType = "text/plain")
        )
      ),
      new ApiResponse(responseCode = "500",
                      description = "Failure - application is not ready",
                      content = Array(new Content(mediaType = "text/plain")))
    )
  )
  @GET
  def readiness: Route

}
