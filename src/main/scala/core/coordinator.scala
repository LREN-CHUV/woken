package core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.Status.Failure
import akka.actor._
import akka.event.LoggingReceive
import akka.util.Timeout
import api.JobDto
import core.clients.{JobClientService, ChronosService}
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
  type WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobComplete = JobClientService.JobComplete

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  case class Results(
                    code: String,
                    date: String,
                    header: Seq[String],
                    data: Stats
                    ) extends RestMessage

  sealed trait Data

  final case class Stats(
                    min: Seq[Double],
                    max: Seq[Double],
                    median: Seq[Double],
                    q1: Seq[Double],
                    q3: Seq[Double]
                    ) extends Data

  case class ErrorResponse(message: String) extends RestMessage

  import BoxPlotResult._
  implicit val statsFormat = jsonFormat5(Stats.apply)
  implicit val resultsFormat = jsonFormat4(Results.apply)
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)
}

/**
 * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
 *
 * This actor will have the responsibility of making two requests and then aggregating them together:
 *  - One request to Chronos to start the job
 *  - Then a separate request in the database for the results, repeated until enough results are present
 */
class CoordinatorActor(val chronosService: ActorRef, val databaseService: ActorRef) extends Actor with ActorLogging {

  import CoordinatorActor._

  // Coordinator actor is created per request, it's safe to store mutable state here
  var replyTo: ActorRef = _
  var nodes: Set[String] = _

  def receive: Receive = LoggingReceive {
    case Start(job) => {

      import config.Config

      nodes = if (job.nodes.isEmpty) Config.jobs.nodes else job.nodes
      val expectedNodeCount = nodes.size

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = Set()))
        }
        context.become(waitForNodes(job.requestId, expectedNodeCount))
      } else {
        scheduleJobOnLocalCluster(job)
      }

    }

  }

  def scheduleJobOnLocalCluster(job: JobDto): Unit = {
    import ChronosService._
    val chronosJob: ChronosJob = JobToChronos.enrich(job)

    replyTo = sender()
    chronosService ! Schedule(chronosJob)
    context.become(waitForChronos(job.requestId))
  }

  def waitForNodes(requestId: String, expectedNodeCount: Int): Receive = {
    case WorkerJobComplete(node) => {
      nodes = nodes - node
      context.become(waitForDataSet(requestId, expectedNodeCount))
    }
    case e: Timeout => context.parent ! Error("Timeout while connecting to remote node")
    case e: Error => {
      log.error(e.message)
      replyTo ! e
    }
    case e => log.error(s"Unhandled message: $e")
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

    val checkSchedule: Cancellable = context.system.scheduler.schedule(100.milliseconds, 200.milliseconds, self, CheckDb)
    val receive = LoggingReceive {

      case CheckDb => {
        log.debug("Checking database...")
        databaseService ! GetBoxPlotResults(requestId)
      }

      case BoxPlotResults(data) if data.nonEmpty => {
        checkSchedule.cancel()
        reply(data, requestId)
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

  def reply(values: Seq[BoxPlotResult], requestId: String) = replyTo ! Results(code = requestId, date = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now()),
    header = Seq("name", "min", "q1", "median", "q3", "max"),
    data = Stats(
      min = values.map(_.min),
      q1 = values.map(_.q1),
      median = values.map(_.median),
      q3 = values.map(_.q3),
      max = values.map(_.max)
    )
  )

}
