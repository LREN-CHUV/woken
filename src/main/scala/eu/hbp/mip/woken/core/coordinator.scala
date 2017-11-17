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

import akka.actor.FSM.Failure
import akka.actor._
import com.github.levkhomich.akka.tracing.ActorTracing
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller

import scala.concurrent.duration._
import eu.hbp.mip.woken.api._
import eu.hbp.mip.woken.core.CoordinatorActor.Start
import eu.hbp.mip.woken.core.clients.{ ChronosService, JobClientService }
import eu.hbp.mip.woken.core.model.{ ChronosJob, JobResult, JobToChronos }
import eu.hbp.mip.woken.dao.JobResultsDAL
import spray.json.{ JsonFormat, RootJsonFormat }

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
    federationDatabase
      .map(
        fd =>
          Props(classOf[FederationCoordinatorActor],
                chronosService,
                resultDatabase,
                fd,
                jobResultsFactory)
      )
      .getOrElse(
        Props(classOf[LocalCoordinatorActor], chronosService, resultDatabase, jobResultsFactory)
      )

}

/** FSM States and internal data */
object CoordinatorStates {

  sealed trait State

  case object WaitForNewJob extends State

  case object WaitForChronos extends State

  case object WaitForNodes extends State

  case object RequestFinalResult extends State

  case object RequestIntermediateResults extends State

  trait StateData {
    def job: JobDto
  }

  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class WaitingForNodesData(job: JobDto,
                                 replyTo: ActorRef,
                                 remainingNodes: Set[String] = Set(),
                                 totalNodeCount: Int)
      extends StateData

  case class WaitLocalData(job: JobDto, replyTo: ActorRef) extends StateData

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

  def chronosService: ActorRef
  def resultDatabase: JobResultsDAL
  def jobResultsFactory: JobResults.Factory

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForChronos) {
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

  when(RequestFinalResult, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitLocalData) => {
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        data.replyTo ! jobResultsFactory(results)
        stop()
      } else {
        stay() forMax repeatDuration
      }
    }
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

  }

  onTransition(transitions)

}

/**
  *  _________________           _________________                    ____________________
  * |                 | Start   |                 | Even(Ok, data)   |                    |
  * | WaitForNewJob   | ------> | WaitForChronos  |----------------> | RequestFinalResult | ==> results
  * | (Uninitialized) |         | (WaitLocalData) |                  |                    |
  *  -----------------           -----------------                    --------------------
  */
class LocalCoordinatorActor(val chronosService: ActorRef,
                            val resultDatabase: JobResultsDAL,
                            val jobResultsFactory: JobResults.Factory)
    extends CoordinatorActor {
  import CoordinatorStates._

  log.info("Local coordinator actor started...")

  when(WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      goto(WaitForChronos) using WaitLocalData(job, sender())
    }
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
    case Event(Start(job), data: StateData) => {
      import eu.hbp.mip.woken.config.WokenConfig
      val replyTo = sender()
      val nodes   = job.nodes.filter(_.isEmpty).getOrElse(WokenConfig.jobs.nodes)

      log.warning(s"List of nodes: ${nodes.mkString(",")}")

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
  when(WaitForNodes) {
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

  when(RequestIntermediateResults, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitingForNodesData) => {
      val results = federationDatabase.findJobResults(data.job.jobId)
      if (results.size == data.totalNodeCount) {
        data.job.federationDockerImage.fold {
          data.replyTo ! PutJobResults(results)
          stop()
        } { federationDockerImage =>
          val parameters = Map(
            "PARAM_query" -> s"select data from job_result_nodes where job_id='${data.job.jobId}'"
          )
          goto(WaitForChronos) using WaitLocalData(
            data.job.copy(dockerImage = federationDockerImage, parameters = parameters),
            data.replyTo
          )
        }
      } else {
        stay() forMax repeatDuration
      }
    }
  }

  initialize()

}
