package router

import com.wordnik.swagger.annotations._
import spray.routing._

/**
 * Document the API, try to to pollute the code with annotations
 */
@Api(value = "/job", description = "Operations for jobs.", consumes= "application/json",  produces = "application/json")
trait JobRouterDoc {

  @ApiOperation(
    value = "Init a job",
    notes = "Init a job and return id",
    httpMethod = "PUT",
    consumes="application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", value="Process to execute", required = true, dataType = "router.JobDto", paramType = "body" )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Job created"),
    new ApiResponse(code = 405, message = "Invalid job"),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def initJob: Route

}
