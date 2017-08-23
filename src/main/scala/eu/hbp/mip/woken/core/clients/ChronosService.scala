package eu.hbp.mip.woken.core.clients

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{ActorLogging, Actor, Status}
import akka.io.IO
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import eu.hbp.mip.woken.core.model.ChronosJob

object ChronosService {
  // Requests
  case class Schedule(job: ChronosJob)

  // Responses
  val Ok = eu.hbp.mip.woken.core.Ok
  type Error = eu.hbp.mip.woken.core.Error
  val Error = eu.hbp.mip.woken.core.Error
}

class ChronosService extends Actor with ActorLogging {
  import ChronosService._
  import eu.hbp.mip.woken.config.WokenConfig.jobs._

  def receive = {
    case Schedule(job) => {
      import akka.pattern.{ask, pipe}
      import spray.httpx.SprayJsonSupport._
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