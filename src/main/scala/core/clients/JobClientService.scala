package core.clients

import akka.actor.{ActorLogging, AbstractLoggingActor, Actor}
import akka.io.IO
import akka.util.Timeout
import models.ChronosJob
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.concurrent.duration._

object JobClientService {

  // Requests
  type Start = core.CoordinatorActor.Start
  val Start = core.CoordinatorActor.Start

  // Responses

  case class JobComplete(node: String)
  case class JobError(node: String, message: String)
}

class JobClientService(node: String) extends Actor with ActorLogging {
  import JobClientService._
  import config.Config.jobs._

  def receive = {
    case Start(job) => {
      import akka.pattern.{ask, pipe}
      import spray.httpx.SprayJsonSupport._
      import api.JobDto._
      implicit val system = context.system
      implicit val executionContext = context.dispatcher
      implicit val timeout: Timeout = Timeout(180.seconds)

      log.warning(s"Send PUT request to ${nodeConfig(node).jobsUrl}/job")
      log.warning(jobDtoFormat.write(job).prettyPrint)

      val originalSender = sender()
      val jobResponse: Future[HttpResponse] =
        (IO(Http) ? Put(nodeConfig(node).jobsUrl + "/job", job)).mapTo[HttpResponse]

      jobResponse.map {
        case HttpResponse(statusCode: StatusCode, entity, _, _) => statusCode match {
          case ok: StatusCodes.Success => JobComplete(node)
          case _ => JobError(node, s"Error $statusCode: ${entity.asString}")
        }
      } recoverWith { case e: Throwable => Future(JobError(node, e.toString)) } pipeTo originalSender
    }

  }
}
