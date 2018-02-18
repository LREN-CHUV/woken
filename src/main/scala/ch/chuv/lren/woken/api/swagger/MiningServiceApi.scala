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
import ch.chuv.lren.woken.messages.query.{ MethodsResponse, QueryResult }
import io.swagger.annotations._

// This trait documents the API, tries not to pollute the code with annotations

/**
  * Operations for data mining
  */
@Api(value = "/mining", consumes = "application/json", produces = "application/json")
trait MiningServiceApi extends Directives {

  @ApiOperation(
    value = "Run a data mining job",
    notes = "Run a data mining job and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[QueryResult]
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "body",
                           value = "Process to execute",
                           required = true,
                           dataType = "eu.hbp.mip.messages.external.MiningQuery",
                           paramType = "body")
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Mining job initialized",
                      response = classOf[QueryResult]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 405, message = "Invalid mining job", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def mining: Route

  @Path("/experiment")
  @ApiOperation(
    value = "Run a data mining experiment",
    notes = "Run a data mining experiment and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[QueryResult]
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "body",
                           value = "Process to execute",
                           required = true,
                           dataType = "eu.hbp.mip.messages.external.ExperimentQuery",
                           paramType = "body")
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Experiment initialized",
                      response = classOf[QueryResult]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 405, message = "Invalid Experiment", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def experiment: Route

  @Path("/methods")
  @ApiOperation(
    value = "Get mining method complete catalog",
    notes = "Get catalog containing available mining methods",
    httpMethod = "GET",
    consumes = "application/json",
    response = classOf[MethodsResponse]
  )
  @ApiImplicitParams(Array())
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Experiment initialized",
                      response = classOf[spray.json.JsObject]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 404, message = "Not Found", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def listMethods: Route

}
