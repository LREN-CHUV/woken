package router

import com.wordnik.swagger.annotations._
import play.api.routing.Router

/**
 * Document the API, try to to pollute the code with annotations
 */
trait JobRouterDoc {

  @ApiOperation(
    nickname = "initJob",
    value = "Init a job",
    notes = "Init a job and return id",
    httpMethod = "PUT")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "OK"),
    new ApiResponse(code = 400, message = "Invalid params"),
    new ApiResponse(code = 405, message = "Job exists"),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", value="Process to execute", required = true, dataType = "router.JobDTO", paramType = "body" )
  ))
  def initJob: Router

}
