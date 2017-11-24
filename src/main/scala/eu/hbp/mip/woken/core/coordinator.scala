/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core

import akka.actor.FSM.{Failure, Normal}
import akka.actor._
import com.github.levkhomich.akka.tracing.ActorTracing
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller

import scala.concurrent.duration._
import eu.hbp.mip.woken.api._
import eu.hbp.mip.woken.core.CoordinatorActor.Start
import eu.hbp.mip.woken.core.clients.{ChronosService, JobClientService}
import eu.hbp.mip.woken.core.model.{ChronosJob, JobResult, JobToChronos}
import eu.hbp.mip.woken.dao.JobResultsDAL
import spray.json.{JsonFormat, RootJsonFormat}

/**
  * We use the companion object to hold all the messages that the ``CoordinatorActor``
  * receives.
  */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: JobDto) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  type WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobError    = JobClientService.JobError

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  type Result = eu.hbp.mip.woken.core.model.JobResult
  val Result = eu.hbp.mip.woken.core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[ErrorResponse] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(
        jsonFormat1(ErrorResponse)
      )
  }

  import JobResult._
  implicit val resultFormat: JsonFormat[Result]                   = JobResult.jobResultFormat
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)

  def props(chronosService: ActorRef,
            resultDatabase: JobResultsDAL,
            federationDatabase: Option[JobResultsDAL],
            jobResultsFactory: JobResults.Factory): Props =
    federationDatabase.fold(
      Props(classOf[LocalCoordinatorActor], chronosService, resultDatabase, jobResultsFactory)
    ) { fd =>
      Props(classOf[FederationCoordinatorActor],
            chronosService,
            resultDatabase,
            fd,
            jobResultsFactory)

    }

  def actorName(job: JobDto): String =
    s"LocalCoordinatorActor_job_${job.jobId}_${job.jobNameResolved}"

}

/** FSM States and internal data */
object CoordinatorStates {

  // FSM States

  sealed trait State

  case object WaitForNewJob extends State

  case object PostJobToChronos extends State

  case object WaitForNodes extends State

  case object RequestFinalResult extends State

  case object RequestIntermediateResults extends State

  // FSM Data

  trait StateData {
    def job: JobDto
  }

  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class PartialNodesData(job: JobDto,
                              replyTo: ActorRef,
                              remainingNodes: Set[String] = Set(),
                              totalNodeCount: Int) extends StateData

  case class PartialLocalData(job: JobDto, replyTo: ActorRef) extends StateData

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of making two requests and then aggregating them together:
  *  - One request to Chronos to start the job
  *  - Then a separate request in the database for the results, repeated until enough results are present
  */
trait CoordinatorActor
    extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[CoordinatorStates.State, CoordinatorStates.StateData] {
  import CoordinatorStates._

  val repeatDuration: FiniteDuration = 200.milliseconds
  val startTime: Long = System.currentTimeMillis

  def chronosService: ActorRef
  def resultDatabase: JobResultsDAL
  def jobResultsFactory: JobResults.Factory

  startWith(WaitForNewJob, Uninitialized)

  when(PostJobToChronos) {

    case Event(Ok, data: PartialLocalData) =>
      log.info(s"Job ${data.job.jobId} posted to Chronos")
      goto(RequestFinalResult) using data

    case Event(e: Error, data: PartialLocalData) =>
      val msg = s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, received error: ${e.message}"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))

    case Event(_: Timeout @unchecked, data: PartialLocalData) =>
      val msg = s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, timeout while connecting to Chronos"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  when(RequestFinalResult, stateTimeout = repeatDuration) {

    case Event(StateTimeout, data: PartialLocalData) =>
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        log.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! jobResultsFactory(results)
        log.info("Stopping...")
        stop(Normal)
      } else {
        stay() forMax repeatDuration
      }
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("Received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  def transitions: TransitionHandler = {

    case _ -> PostJobToChronos =>
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(nextStateData.job)
      chronosService ! Schedule(chronosJob)

  }

  onTransition(transitions)

}

/**
  *  _________________           ____________________                    ____________________
  * |                 | Start   |                    | Even(Ok, data)   |                    |
  * | WaitForNewJob   | ------> | PostJobToChronos   |----------------> | RequestFinalResult | ==> results
  * | (Uninitialized) |         | (PartialLocalData) |                  |                    |
  *  -----------------           --------------------                    --------------------
  */
class LocalCoordinatorActor(val chronosService: ActorRef,
                            val resultDatabase: JobResultsDAL,
                            val jobResultsFactory: JobResults.Factory)
    extends CoordinatorActor {
  import CoordinatorStates._

  log.info("Local coordinator actor started...")

  when(WaitForNewJob) {
    case Event(Start(job), Uninitialized) =>
      val replyTo = sender()
      log.info(s"Wait for Chronos to fulfill job ${job.jobId}, will reply to $replyTo")
      goto(PostJobToChronos) using PartialLocalData(job, replyTo)
  }

  when(WaitForNodes) {
    case _ => stop(Failure("Unexpected state WaitForNodes"))
  }

  when(RequestIntermediateResults) {
    case _ => stop(Failure("Unexpected state RequestIntermediateResults"))
  }

  initialize()

}

class FederationCoordinatorActor(val chronosService: ActorRef,
                                 val resultDatabase: JobResultsDAL,
                                 val federationDatabase: JobResultsDAL,
                                 val jobResultsFactory: JobResults.Factory)
    extends CoordinatorActor {

  import CoordinatorActor._
  import CoordinatorStates._

  when(WaitForNewJob) {

    case Event(Start(job), Uninitialized) =>
      import eu.hbp.mip.woken.config.WokenConfig
      val replyTo = sender()
      val nodes   = job.nodes.filter(_.isEmpty).getOrElse(WokenConfig.jobs.nodes)

      log.warning(s"List of nodes: ${nodes.mkString(",")}")

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = None))
        }
        goto(WaitForNodes) using PartialNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(PostJobToChronos) using PartialLocalData(job, replyTo)
      }
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when(WaitForNodes) {

    case Event(WorkerJobComplete(node), data: PartialNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }

    case Event(WorkerJobError(node, message), data: PartialNodesData) =>
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
  }

  when(RequestIntermediateResults, stateTimeout = repeatDuration) {

    case Event(StateTimeout, data: PartialNodesData) =>
      val results = federationDatabase.findJobResults(data.job.jobId)
      if (results.size == data.totalNodeCount) {
        data.job.federationDockerImage.fold {
          data.replyTo ! PutJobResults(results)
          stop()
        } { federationDockerImage =>
          val parameters = Map(
            "PARAM_query" -> s"select data from job_result_nodes where job_id='${data.job.jobId}'"
          )
          goto(PostJobToChronos) using PartialLocalData(
            data.job.copy(dockerImage = federationDockerImage, parameters = parameters),
            data.replyTo
          )
        }
      } else {
        stay() forMax repeatDuration
      }
  }

  initialize()

}
