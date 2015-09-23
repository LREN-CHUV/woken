package core

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, Cancellable, ActorRef, Actor}
import akka.event.LoggingReceive
import akka.util.Timeout
import api.JobDto
import core.clients.ChronosService
import core.model.JobToChronos
import core.model.results.BoxPlotResult
import models.ChronosJob

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * We use the companion object to hold all the messages that the ``CoordinatorActor``
 * receives.
 */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: JobDto) extends RestMessage

  // Responses, to wrap in Either
  case class Results(values: Seq[BoxPlotResult]) extends RestMessage
  case class ErrorResponse(message: String) extends RestMessage

  import BoxPlotResult._
  implicit val resultsFormat = jsonFormat1(Results.apply)
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)
}

class CoordinatorActor(val chronosService: ActorRef, val databaseService: ActorRef) extends Actor with ActorLogging {

  import CoordinatorActor._
  var replyTo: ActorRef = _

  def receive: Receive = LoggingReceive {
    case Start(job) => {
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(job)

      replyTo = sender()
      chronosService ! Schedule(chronosJob)
      context.become(waitForChronos(job.requestId))
    }

  }

  def waitForChronos(requestId: String): Receive = {
    case Ok => context.become(waitForData(requestId))
    case e: Timeout => context.parent ! Error("Timeout while connecting to Chronos")
    case e: Error => {
      log.error(e.message)
      replyTo ! e
    }
    case e => log.error(s"Unhandled message: $e")
  }

  def waitForData(requestId: String): Receive = {
    import core.clients.DatabaseService._
    implicit val executionContext: ExecutionContext = context.dispatcher

    // internal message
    object CheckDb
    val checkSchedule: Cancellable = context.system.scheduler.schedule(100.milliseconds, 200.milliseconds, self, CheckDb)
    val receive = LoggingReceive {

      case CheckDb => {
        println("Check database...")
        databaseService ! GetBoxPlotResults(requestId)
      }

      case BoxPlotResults(data) if data.nonEmpty => {
        checkSchedule.cancel()
        reply(data)
      }
      case BoxPlotResults(_) => ()

      case Failure(t) => {
        checkSchedule.cancel()
        log.error(t, "Database error")
        replyTo ! Error(t.toString)
      }
      case e: Timeout => {
        checkSchedule.cancel()
        replyTo ! Error("Timeout while connecting to Chronos")
      }
      case e: Error => {
        checkSchedule.cancel()
        log.error(e.message)
        replyTo ! e
      }
      case e => log.error(s"Unhandled message: $e")
    }
    receive
  }

  def reply(values: Seq[BoxPlotResult]) = replyTo ! Results(values)

}
