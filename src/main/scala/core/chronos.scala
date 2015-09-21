package core

import akka.actor.{Cancellable, ActorRef, Actor}
import akka.io.IO
import akka.util.Timeout
import api.ResultDto._
import api.JobDto
import core.model.JobToChronos
import core.model.results.BoxPlotResult
import dao.BoxPlotResultDao
import models.ChronosJob
import spray.can.Http
import spray.http.{StatusCodes, StatusCode, HttpResponse}
import spray.httpx.RequestBuilding._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import config.DatabaseConfig._

/**
 * We use the companion object to hold all the messages that the ``ChronosActor``
 * receives.
 */
object ChronosActor {

  // Incoming messages
  case class Start(job: JobDto)
  object CheckDb

  // Responses, to wrap in Either
  case class Results(values: Seq[BoxPlotResult])
  case class ErrorResponse(message: String)

  import BoxPlotResult._
  implicit val resultsFormat = jsonFormat1(Results.apply)
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)
}

class ChronosActor(val chronosServerUrl: String, val bpResultDao: BoxPlotResultDao) extends Actor {

  import ChronosActor._

  def receive: Receive = {
    case Start(job) => {
      val chronosJob: ChronosJob = JobToChronos.enrich(job)

      implicit val timeout: Timeout = Timeout(15.seconds)
      implicit val system = context.system
      implicit val executionContext: ExecutionContext = system.dispatcher
      import akka.pattern.ask
      import akka.pattern.pipe
      import spray.httpx.SprayJsonSupport._
      import ChronosJob._

      val replyTo = sender()
      println(chronosJobFormat.write(chronosJob).prettyPrint)
      val chronosResponse: Future[(HttpResponse, String, ActorRef)] =
        (IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", chronosJob)).mapTo[HttpResponse].map((_, job.requestId, replyTo))

      chronosResponse pipeTo self
    }

    case (HttpResponse(statusCode: StatusCode, entity, _, _), requestId: String, replyTo: ActorRef) => statusCode match {
      case ok: StatusCodes.Success => {
        println (s"Received $statusCode from Chronos, waiting for data...")
        implicit val executionContext: ExecutionContext = context.system.dispatcher
        context.become(waitForData(requestId, replyTo, context.system.scheduler.schedule(100.milliseconds, 200.milliseconds, self, CheckDb)), discardOld = false)
      }
      case _ => {
        println (s"Error $statusCode: ${entity.asString}")
        replyTo ! Left(ErrorResponse(s"$statusCode: ${entity.asString}"))
      }
    }
  }

  def waitForData(requestId: String, replyTo: ActorRef, checkSchedule: Cancellable): Receive = {

    case CheckDb => {
      println("Check database...")
      checkSchedule.cancel()
      context.unbecome()
      replyTo ! Right(Results(Nil))
      /*
      implicit val executionContext: ExecutionContext = context.system.dispatcher
      val results = db.run {
        for {
          results <- bpResultDao.get(requestId)
        } yield results
      }

      results
        .onFailure { case e: Throwable =>
          checkSchedule.cancel()
          context.unbecome()
          println (s"Database error: $e")
          replyTo ! Left(ErrorResponse(e.toString))
      }

      results.filter(_.nonEmpty).foreach { res =>
        checkSchedule.cancel()
        context.unbecome()
        println (s"Response: $res")
        replyTo ! Right(Results(res))
      }
      */
    }

  }

}
