package api

import javax.ws.rs.Path

import com.wordnik.swagger.annotations.{Api => SwaggerApi, _}
import core.CoordinatorActor.Result
import core.CoordinatorActor.{Result => ExperimentResult}
import spray.routing._

/**
 * Document the API, try not to pollute the code with annotations
 */
@SwaggerApi(value = "/mining", description = "Operations for data mining.", consumes = "application/json",  produces = "application/json")
trait MiningServiceDoc extends Directives {

  @ApiOperation(
    value = "Run a data mining job",
    notes = "Run a data mining job and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[Result]
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", value = "Process to execute", required = true, dataType = "api.SimpleQuery", paramType = "body")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Mining job initialized", response = classOf[Result]),
    new ApiResponse(code = 405, message = "Invalid mining job", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
  ))
  def mining: Route

  @Path("/experiment")
  @ApiOperation(
    value = "Run a data mining experiment",
    notes = "Run a data mining experiment and return id",
    httpMethod = "POST",
    consumes="application/json",
    response = classOf[Result]
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", value="Process to execute", required = true, dataType = "api.ExperimentQuery", paramType = "body" )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Experiment initialized", response = classOf[ExperimentResult]),
    new ApiResponse(code = 405, message = "Invalid Experiment", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
  ))
  def experiment: Route

  @Path("/list-methods")
  @ApiOperation(
    value = "Get mining method complete catalog",
    notes = "Get catalog containing available mining methods",
    httpMethod = "GET",
    consumes = "application/json",
    response = classOf[Result]
  )
  @ApiImplicitParams(Array())
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Experiment initialized", response = classOf[spray.json.JsObject]),
    new ApiResponse(code = 404, message = "Not Found", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
  ))
  def listMethods: Route
}

