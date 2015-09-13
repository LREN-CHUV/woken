package router

// swagger imports
import com.wordnik.swagger.annotations._

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.mvc.Results._
import play.api.libs.json._

import models._
import globals._

import javax.inject._
import javax.ws.rs.{QueryParam, PathParam}
import scala.concurrent.Future

class JobController extends Controller {

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
    new ApiImplicitParam (
      name = "email", value = "email of the user to init",
      required = true, dataType = "String", paramType = "body"),
    new ApiImplicitParam (
      name = "sex", value = "sex of the user to init",
      required = true, dataType = "String", paramType = "body"),
    new ApiImplicitParam (
      name = "passwd", value = "password the user input",
      required = true, dataType = "String", paramType = "body")
  ))
  def initJob = Action.async(parse.json) { implicit request =>
    val fn = (process: Process) => {
      Future(Ok)
      /*TaskModel.store.update(Task(Some(id), txt, done)).map{ r =>
        if(r) Ok else BadRequest*/
      }.recover{ case e => InternalServerError(e)}
    executeProcess(fn)
  }

  def executeProcess(fn: Process => Future[Result])
                    (implicit request: Request[JsValue]) = {
    request.body.validate[Process].map {
      case p: Process => {
        fn(p)
      }
    }.recoverTotal{
      e => Future(BadRequest(e))
    }
  }
}
