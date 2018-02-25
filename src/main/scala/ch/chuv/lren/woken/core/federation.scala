/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.core

import akka.actor.{ Actor, ActorLogging, ActorRef, LoggingFSM }
//import com.github.levkhomich.akka.tracing.ActorTracing
import ch.chuv.lren.woken.backends.DockerJob

import scala.concurrent.duration._
import scala.language.postfixOps

object FederationCoordinatorActor {
  val repeatDuration: FiniteDuration = 1 minute

  // Incoming messages
  // TODO: define a new job type for distributed job
  case class Start(job: DockerJob)
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

class FederationCoordinatorActor()
    extends Actor
    with ActorLogging
    /*with ActorTracing*/
    with LoggingFSM[FederationCoordinatorStates.State, FederationCoordinatorStates.StateData] {
  {

    import FederationCoordinatorActor._
    import FederationCoordinatorStates._

    when(WaitForNewJob) {

      case Event(Start(job), Uninitialized) =>
        /*
        import ch.chuv.lren.woken.config.WokenConfig
        val replyTo = sender()
        val nodes = job.nodes.filter(_.isEmpty).getOrElse(WokenConfig.jobs.nodes)

        log.warning(s"List of nodes: ${nodes.mkString(",")}")

        if (nodes.nonEmpty) {
          for (node <- nodes) {
            val workerNode = context.actorOf(Props(classOf[WokenService], node))
            //FIXME: workerNode ! Start(job.copy(nodes = nodes - node))
          }
          goto(WaitForNodes) using PartialNodesData(job, replyTo, nodes, nodes.size)
        } else {
          goto(PostJobToChronos) using PartialLocalData(job, replyTo)
        }
         */
        stop()
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
        /*
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
         */
        stop()
    }

    initialize()

  }
}
