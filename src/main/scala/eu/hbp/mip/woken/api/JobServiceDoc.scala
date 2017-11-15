/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import spray.routing._
import com.wordnik.swagger.annotations.{ Api => SwaggerApi, _ }

import eu.hbp.mip.woken.core.CoordinatorActor.Result

/**
  * Document the API, try not to pollute the code with annotations
  */
@SwaggerApi(value = "/job",
            description = "Operations for jobs.",
            consumes = "application/json",
            produces = "application/json")
trait JobServiceDoc extends Directives {

  @ApiOperation(
    value = "Init a job",
    notes = "Init a job and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[Result]
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "body",
                           value = "Process to execute",
                           required = true,
                           dataType = "api.JobDto",
                           paramType = "body")
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 201, message = "Job created", response = classOf[Result]),
      new ApiResponse(code = 405, message = "Invalid job", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  def initJob: Route
}
