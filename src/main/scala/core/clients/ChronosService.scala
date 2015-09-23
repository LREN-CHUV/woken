package core.clients

import akka.actor.Actor
import akka.io.IO
import akka.util.Timeout
import models.ChronosJob
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.concurrent.duration._
import core.{Ok, Error}

object ChronosService {
  case class Schedule(job: ChronosJob)
}

class ChronosService extends Actor {
  import ChronosService._
  import config.Config.jobs._

  def receive = {
    case Schedule(job) => {
      import akka.pattern.{ask, pipe}
      import spray.httpx.SprayJsonSupport._
      import ChronosJob._
      implicit val system = context.system
      implicit val executionContext = context.dispatcher
      implicit val timeout: Timeout = Timeout(15.seconds)

      val originalSender = sender()
      val chronosResponse: Future[HttpResponse] =
        (IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", job)).mapTo[HttpResponse]

      chronosResponse.map {
        case HttpResponse(statusCode: StatusCode, entity, _, _) => statusCode match {
          case ok: StatusCodes.Success => Ok
          case _ => Error(s"Error $statusCode: ${entity.asString}")
        }
      } pipeTo originalSender
    }

  }
}