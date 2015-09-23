package core.clients

import akka.actor.Actor
import akka.io.IO
import akka.util.Timeout
import core.{Error, Ok}
import models.ChronosJob
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.concurrent.duration._

object JobClientService {
  type Start = core.CoordinatorActor.Start
  val Start = core.CoordinatorActor.Start
}

class JobClientService(node: String) extends Actor {
  import JobClientService._
  import config.Config.jobs._

  def receive = {
    case Start(job) => {
      import akka.pattern.{ask, pipe}
      import spray.httpx.SprayJsonSupport._
      import ChronosJob._
      implicit val system = context.system
      implicit val executionContext = context.dispatcher
      implicit val timeout: Timeout = Timeout(180.seconds)

      val originalSender = sender()
      val jobResponse: Future[HttpResponse] =
        (IO(Http) ? Put(nodeConfig(node).jobsUrl + "/job", job)).mapTo[HttpResponse]

      jobResponse.map {
        case HttpResponse(statusCode: StatusCode, entity, _, _) => statusCode match {
          case ok: StatusCodes.Success => Ok
          case _ => Error(s"Error $statusCode: ${entity.asString}")
        }
      } pipeTo originalSender
    }

  }
}
