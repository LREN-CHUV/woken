package api

import com.wordnik.swagger.annotations.{Api => SwaggerApi, _}
import spray.routing._

/**
 * Document the API, try to to pollute the code with annotations
 */
@SwaggerApi(value = "/job", description = "Operations for jobs.", consumes = "application/json",  produces = "application/json")
trait JobServiceDoc extends Directives {

  @ApiOperation(
    value = "Init a job",
    notes = "Init a job and return id",
    httpMethod = "PUT",
    consumes="application/json",
    response = classOf[ResultDto]
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", value="Process to execute", required = true, dataType = "api.JobDto", paramType = "body" )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Job created", response = classOf[ResultDto]),
    new ApiResponse(code = 405, message = "Invalid job", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
  ))
  def initJob: Route

}
