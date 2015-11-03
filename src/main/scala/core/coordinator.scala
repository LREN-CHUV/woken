package core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.FSM.Failure
import akka.actor._
import akka.event.LoggingReceive
import akka.util.Timeout
import api.JobDto
import core.clients.{JobClientService, ChronosService}
import core.model.JobToChronos
import core.model.JobResult
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
  val WorkerJobError = JobClientService.JobError

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  case class Results(
                    code: String,
                    date: String,
                    header: Seq[String] = Seq(),
                    data: Seq[String]
                    ) extends RestMessage

  case class ErrorResponse(message: String) extends RestMessage

  import JobResult._
  implicit val resultsFormat = jsonFormat4(Results.apply)
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)

  def props(chronosService: ActorRef, databaseService: ActorRef): Props =
    Props(classOf[CoordinatorActor], chronosService, databaseService)
}

sealed trait State
case object WaitForNewJob extends State
case object WaitForChronos extends State
case object WaitForFinalResult extends State
case object WaitForNodes extends State
case object WaitForIntermediateResults extends State

trait StateData {
  def job: JobDto
}
case object EmptyStateData extends StateData {
  def job = _
}
case class WaitingForNodesData(job: JobDto, replyTo: ActorRef, remainingNodes: Set[String] = Set(), totalNodeCount: Int) extends StateData
case class WaitLocalData(job: JobDto, replyTo: ActorRef) extends StateData

/**
 * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
 *
 * This actor will have the responsibility of making two requests and then aggregating them together:
 *  - One request to Chronos to start the job
 *  - Then a separate request in the database for the results, repeated until enough results are present
 */
class CoordinatorActor(val chronosService: ActorRef, val databaseService: ActorRef) extends Actor with ActorLogging with FSM[State, StateData] {

  import CoordinatorActor._

  startWith(WaitForNewJob, EmptyStateData)

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      val replyTo = sender()

      import config.Config

      val nodes = if (job.nodes.isEmpty) Config.jobs.nodes else job.nodes

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = Set()))
        }
        goto(WaitForNodes) using WaitingForNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(WaitForChronos) using WaitLocalData(job, replyTo)
      }
    }
  }

  when (WaitForChronos) {
    case Event(Ok, data: WaitLocalData) => goto(WaitForFinalResult) using data
    case Event(e: Timeout, data: WaitLocalData) =>
      val msg: String = "Timeout while connecting to Chronos"
      data.replyTo ! Error(msg)
      stop(Failure(msg))
    case Event(e: Error, data: WaitLocalData) =>
      val msg: String = e.message
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when (WaitForNodes) {
    case Event(WorkerJobComplete(node), data: WaitingForNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(WaitForIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    case Event(WorkerJobError(node, message), data: WaitingForNodesData) => {
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(WaitForIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    }
  }

  when (WaitForIntermediateResults) {

  }

  when (WaitForFinalResult) {

  }

  onTransition {

    case _ -> WaitForChronos =>
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(nextStateData.job)
      chronosService ! Schedule(chronosJob)

    case _ -> WaitForIntermediateResults =>
      

  }

  initialize()

  def reply(values: Seq[String], requestId: String) = replyTo ! Results(code = requestId, date = DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now()),
    header = Seq("name", "min", "q1", "median", "q3", "max"),
    data = values
  )

}
