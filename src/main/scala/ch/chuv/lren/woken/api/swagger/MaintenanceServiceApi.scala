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
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import javax.ws.rs.{ POST, Path }

// This trait documents the API, tries not to pollute the code with annotations

/**
  * Operations for maintenance
  */
@Path("/maintenance")
trait MaintenanceServiceApi extends Directives {

  @Path("/miningCache/prefill")
  @POST
  @Operation(
    summary = "Prefill the cache for mining",
    description =
      "Prefill the cache for mining with histograms and summary statistics for all variables in all registered tables",
    tags = Array("admin"),
    responses = Array(
      new ApiResponse(responseCode = "200",
                      description = "Success",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "500",
                      description = "Internal server error",
                      content = Array(new Content(mediaType = "text/plain")))
    )
    //security = Array(new SecurityRequirement("basicAuth"))
  )
  def prefillMiningCache: Route

  @Path("/miningCache/reset")
  @POST
  @Operation(
    summary = "Full reset the mining cache",
    description =
      "Delete all results stored in the database table results_cache. This table caches results from data mining operations",
    tags = Array("admin"),
    responses = Array(
      new ApiResponse(responseCode = "200",
                      description = "Success",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "500",
                      description = "Internal server error",
                      content = Array(new Content(mediaType = "text/plain")))
    )
    //security = Array(new SecurityRequirement("basicAuth"))
  )
  def resetMiningCache: Route

  @Path("/miningCache/maintain")
  @POST
  @Operation(
    summary = "Run maintenance tasks on the mining cache",
    description =
      "Remove stale results stored in the database table results_cache. This table caches results from data mining operations",
    tags = Array("admin"),
    responses = Array(
      new ApiResponse(responseCode = "200",
                      description = "Success",
                      content = Array(new Content(mediaType = "text/plain"))),
      new ApiResponse(responseCode = "500",
                      description = "Internal server error",
                      content = Array(new Content(mediaType = "text/plain")))
    )
    //security = Array(new SecurityRequirement("basicAuth"))
  )
  def maintainMiningCache: Route

}
