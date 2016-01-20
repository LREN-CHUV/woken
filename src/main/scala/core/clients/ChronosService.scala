package core.clients

import akka.actor.{ActorLogging, Actor, Status}
import akka.io.IO
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import models.ChronosJob
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.concurrent.duration._

object ChronosService {
  // Requests
  case class Schedule(job: ChronosJob)

  // Responses
  val Ok = core.Ok
  type Error = core.Error
  val Error = core.Error
}

class ChronosService extends Actor with ActorLogging {
  import ChronosService._
  import config.Config.jobs._

  def receive = {
    case Schedule(job) => {
      import akka.pattern.{ask, pipe}
      import spray.httpx.SprayJsonSupport._
      import ChronosJob._
      implicit val system = context.system
      implicit val executionContext = context.dispatcher
      implicit val timeout: Timeout = Timeout(30.seconds)

      import ChronosJob._
      log.warning(spray.json.PrettyPrinter.apply(chronosJobFormat.write(job)))
      val originalSender = sender()
        val chronosResponse: Future[_] =
          IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", job)

        chronosResponse.map {
          case HttpResponse(statusCode: StatusCode, entity, _, _) => statusCode match {
            case ok: StatusCodes.Success => Ok
            case _ => {
              log.warning(s"Post to Chronos on $chronosServerUrl returned error $statusCode: ${entity.asString}")
              Error(s"Error $statusCode: ${entity.asString}")
            }
          }
          case f: Status.Failure => {
            log.warning(s"Post to Chronos on $chronosServerUrl returned error ${f.cause.getMessage}")
            Error(f.cause.getMessage)
          }
        }.recover {
          case e: AskTimeoutException => {
            log.warning(s"Post to Chronos on $chronosServerUrl timed out after $timeout")
            Error("Connection timeout")
          }
          case e: Throwable => {
            log.warning(s"Post to Chronos on $chronosServerUrl returned an error $e")
            Error(e.getMessage)
          }
        } pipeTo originalSender
    }

  }
}