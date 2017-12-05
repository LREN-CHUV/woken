package eu.hbp.mip.woken.core

import akka.actor.{Actor, ActorLogging, ActorRef, LoggingFSM, Props}
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.api.ApiJsonSupport
import eu.hbp.mip.woken.backends.{DockerJob, QueryOffset}
import eu.hbp.mip.woken.dao.JobResultsDAL
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.RootJsonFormat

import scala.concurrent.duration._

object FederationCoordinatorActor {
    val repeatDuration: FiniteDuration = 1 minute

  // Incoming messages
  // TODO: define a new job type for distributed job
  case class Start(job: DockerJob) extends RestMessage {
    import ApiJsonSupport._
    import spray.httpx.SprayJsonSupport._
    implicit val queryOffsetFormat: RootJsonFormat[QueryOffset] = jsonFormat2(QueryOffset.apply)
    implicit val jobFormat: RootJsonFormat[DockerJob]           = jsonFormat7(DockerJob.apply)
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }
}

object FederationCoordinatorStates {
  // FSM States

  sealed trait State

  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State

  case object WaitForNodes extends State

  case object PostJobToChronos extends State

  case object RequestIntermediateResults extends State

  case object RequestFinalResult extends State

  case class WorkerJobComplete(node: String) extends State

  case class WorkerJobError(node: String, message: String) extends State

  // FSM Data

  trait StateData {
    def job: DockerJob
    def replyTo: ActorRef
  }

  case object Uninitialized extends StateData {
    def job     = throw new IllegalAccessException()
    def replyTo = throw new IllegalAccessException()
  }

  case class PartialNodesData(job: DockerJob,
                              replyTo: ActorRef,
                              remainingNodes: Set[String] = Set(),
                              totalNodeCount: Int)
    extends StateData

  case class PartialLocalData(job: DockerJob, replyTo: ActorRef) extends StateData

}

class FederationCoordinatorActor(val chronosService: ActorRef,
                                 val resultDatabase: JobResultsDAL,
                                 val federationDatabase: JobResultsDAL,
                                 val jobResultsFactory: JobResults.Factory) extends Actor with ActorLogging
  with ActorTracing
  with LoggingFSM[FederationCoordinatorStates.State, FederationCoordinatorStates.StateData] {
  {

    import FederationCoordinatorActor._
    import FederationCoordinatorStates._

    when(WaitForNewJob) {

      case Event(Start(job), Uninitialized) =>
        import eu.hbp.mip.woken.config.WokenConfig
        val replyTo = sender()
        val nodes = job.nodes.filter(_.isEmpty).getOrElse(WokenConfig.jobs.nodes)

        log.warning(s"List of nodes: ${nodes.mkString(",")}")

        if (nodes.nonEmpty) {
          for (node <- nodes) {
            val workerNode = context.actorOf(Props(classOf[JobClientService], node))
            //FIXME: workerNode ! Start(job.copy(nodes = nodes - node))
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
}
