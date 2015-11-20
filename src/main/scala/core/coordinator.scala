package core

import akka.actor.FSM.Failure
import akka.actor._
import api.JobDto
import core.CoordinatorActor.Start
import core.clients.DatabaseService.{JobResults, GetJobResults}
import core.clients.{JobClientService, ChronosService}
import core.model.JobToChronos
import core.model.JobResult
import models.ChronosJob
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._

/**
 * We use the companion object to hold all the messages that the ``CoordinatorActor``
 * receives.
 */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: JobDto) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[Start] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  type WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobError = JobClientService.JobError

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  type Result = core.model.JobResult
  val Result = core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[ErrorResponse] = ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(jsonFormat1(ErrorResponse))
  }

  import JobResult._
  implicit val resultFormat = jsonFormat5(Result.apply)
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)

  def props(chronosService: ActorRef, resultDatabaseService: ActorRef, federationDatabaseService: Option[ActorRef]): Props =
    federationDatabaseService.map(fds => Props(classOf[FederationCoordinatorActor], chronosService, resultDatabaseService, fds))
      .getOrElse(Props(classOf[LocalCoordinatorActor], chronosService, resultDatabaseService))

}

sealed trait State
case object WaitForNewJob extends State
case object WaitForChronos extends State
case object RequestFinalResult extends State
case object WaitForFinalResult extends State
case object WaitForNodes extends State
case object RequestIntermediateResults extends State
case object WaitForIntermediateResults extends State

trait StateData {
  def job: JobDto
}
case object EmptyStateData extends StateData {
  def job = throw new IllegalAccessException()
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
trait CoordinatorActor extends Actor with ActorLogging with LoggingFSM[State, StateData] {

  def chronosService: ActorRef
  def resultDatabaseService: ActorRef

  startWith(WaitForNewJob, EmptyStateData)

  when (WaitForChronos) {
    case Event(Ok, data: WaitLocalData) => goto(RequestFinalResult) using data
    case Event(e: Error, data: WaitLocalData) =>
      val msg: String = e.message
      data.replyTo ! Error(msg)
      stop(Failure(msg))
    case Event(e: Timeout @unchecked, data: WaitLocalData) =>
      val msg: String = "Timeout while connecting to Chronos"
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  when (RequestFinalResult, stateTimeout = 200.milliseconds) {
    case Event(StateTimeout, _) => goto(WaitForFinalResult)
  }

  when (WaitForFinalResult) {
    case Event(results: JobResults, data: WaitLocalData) =>
      if (results.results.nonEmpty) {
        data.replyTo ! PutJobResults(results.results)
        stop()
      } else goto(RequestFinalResult)

    case Event(failure: Status.Failure, data: WaitLocalData) =>
      log.error(failure.cause, "Cannot query result database")
      val msg: String = failure.cause.getMessage
      data.replyTo ! Error(msg)
      stop(Failure(msg))

    case Event(e: Timeout @unchecked, data: WaitLocalData) =>
      val msg: String = "Timeout while querying result database"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("Received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }


  def transitions: TransitionHandler = {

    case _ -> WaitForChronos =>
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(nextStateData.job)
      chronosService ! Schedule(chronosJob)

    case _ -> WaitForFinalResult =>
      log.debug("Wait for final results")
      resultDatabaseService ! GetJobResults(nextStateData.job.jobId)

  }

  onTransition( transitions )

}

class LocalCoordinatorActor(val chronosService: ActorRef, val resultDatabaseService: ActorRef) extends CoordinatorActor {
  log.info ("Local coordinator actor started...")

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      goto(WaitForChronos) using WaitLocalData(job, sender())
    }
  }

  when (WaitForNodes) {
    case _ => stop(Failure("Unexpected state WaitForNodes"))
  }

  when (RequestIntermediateResults) {
    case _ => stop(Failure("Unexpected state RequestIntermediateResults"))
  }

  when (WaitForIntermediateResults) {
    case _ => stop(Failure("Unexpected state WaitForIntermediateResults"))
  }

  initialize()

}

class FederationCoordinatorActor(val chronosService: ActorRef, val resultDatabaseService: ActorRef, val federationDatabaseService: ActorRef) extends CoordinatorActor {

  import CoordinatorActor._

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      import config.Config
      val replyTo = sender()
      val nodes = job.nodes.getOrElse(Config.jobs.nodes)

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = None))
        }
        goto(WaitForNodes) using WaitingForNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(WaitForChronos) using WaitLocalData(job, replyTo)
      }
    }
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when (WaitForNodes) {
    case Event(WorkerJobComplete(node), data: WaitingForNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    case Event(WorkerJobError(node, message), data: WaitingForNodesData) => {
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    }
  }

  when (RequestIntermediateResults, stateTimeout = 200.milliseconds) {
    case Event(StateTimeout, _) => goto(WaitForIntermediateResults)
  }

  when (WaitForIntermediateResults) {
    case Event(results: JobResults, data: WaitingForNodesData) =>
      if (results.results.size == data.totalNodeCount) {
        goto(WaitForChronos) using WaitLocalData(data.job, data.replyTo)
      } else goto(RequestIntermediateResults)

    case Event(failure: Status.Failure, data: WaitingForNodesData) =>
      log.error(failure.cause, "Cannot query federated database")
      val msg: String = failure.cause.getMessage
      data.replyTo ! Error(msg)
      stop(Failure(msg))

    case Event(e: Timeout @unchecked, data: WaitingForNodesData) =>
      val msg: String = "Timeout while querying federated database"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  override def transitions = super.transitions orElse {
    case _ -> WaitForIntermediateResults =>
      log.debug("Wait for intermediate results")
      federationDatabaseService ! GetJobResults(nextStateData.job.jobId)
  }

  initialize()

}