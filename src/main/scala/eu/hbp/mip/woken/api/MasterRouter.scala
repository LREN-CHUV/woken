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

package eu.hbp.mip.woken.api

import java.time.OffsetDateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig, ExperimentActor }
import FunctionsInOut._
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.api.MasterRouter.QueuesSize
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.WokenConfig
import eu.hbp.mip.woken.core.model.{ ErrorJobResult, JobResult }
import eu.hbp.mip.woken.service.{ AlgorithmLibraryService, VariablesMetaService }

object MasterRouter {

  // Incoming messages
  case object RequestQueuesSize

  // Responses
  case class QueuesSize(experiments: Int, mining: Int) {
    def isEmpty: Boolean = experiments == 0 && mining == 0
  }

  def props(coordinatorConfig: CoordinatorConfig,
            variablesMetaService: VariablesMetaService,
            algorithmLibraryService: AlgorithmLibraryService): Props =
    Props(
      new MasterRouter(coordinatorConfig,
                       algorithmLibraryService,
                       experimentQuery2job(variablesMetaService),
                       miningQuery2job(variablesMetaService))
    )

}

case class MasterRouter(coordinatorConfig: CoordinatorConfig,
                        algorithmLibraryService: AlgorithmLibraryService,
                        query2jobF: ExperimentQuery => ExperimentActor.Job,
                        query2jobFM: MiningQuery => DockerJob)
    extends Actor
    with ActorTracing
    with ActorLogging {

  import MasterRouter.RequestQueuesSize

  var experimentActiveActors: Set[ActorRef]                      = Set.empty
  val experimentActiveActorsLimit: Int                           = WokenConfig.app.masterRouterConfig.miningActorsLimit
  var experimentJobsInFlight: Map[ExperimentActor.Job, ActorRef] = Map()

  var miningActiveActors: Set[ActorRef]            = Set.empty
  val miningActiveActorsLimit: Int                 = WokenConfig.app.masterRouterConfig.experimentActorsLimit
  var miningJobsInFlight: Map[DockerJob, ActorRef] = Map()

  def receive: PartialFunction[Any, Unit] = {

    // TODO: MethodsQuery should be case object
    case _: MethodsQuery =>
      sender ! Methods(algorithmLibraryService.algorithms().compactPrint)

    case MiningQuery(variables, covariables, groups, _, Algorithm(c, n, p))
        if c == "" || c == "data" =>
    // TODO To be implemented

    case query: MiningQuery =>
      if (miningActiveActors.size <= miningActiveActorsLimit) {
        val miningActorRef = newCoordinatorActor
        val job            = query2jobFM(query)
        miningActorRef ! CoordinatorActor.Start(job)
        context watch miningActorRef
        miningActiveActors += miningActorRef
        miningJobsInFlight += (job -> sender())
      } else {
        sender() ! List(
          ErrorJobResult("",
                         "",
                         OffsetDateTime.now(),
                         query.algorithm.code,
                         "Too busy to accept new jobs.")
        )
      }

    case CoordinatorActor.Response(job, results) =>
      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      val jobResult = results.head
      val initiator = miningJobsInFlight.get(job)
      miningJobsInFlight -= job
      initiator.get ! JobResult.asQueryResult(jobResult)

    case query: ExperimentQuery =>
      log.debug(s"Received message: $query")
      if (experimentActiveActors.size <= experimentActiveActorsLimit) {
        val experimentActorRef = newExperimentActor
        val job                = query2jobF(query)
        experimentActorRef ! ExperimentActor.Start(job)
        context watch experimentActorRef
        experimentActiveActors += experimentActorRef
        experimentJobsInFlight += (job -> sender())
      } else {
        val error =
          ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.")
        sender() ! JobResult.asQueryResult(error)
      }

    case ExperimentActor.Response(job, Left(results)) =>
      log.info(s"Received experiment error response $results")
      val initiator = experimentJobsInFlight.get(job)
      experimentJobsInFlight -= job
      initiator.get ! JobResult.asQueryResult(results)

    case ExperimentActor.Response(job, Right(results)) =>
      log.info(s"Received experiment response $results")
      val initiator = experimentJobsInFlight.get(job)
      experimentJobsInFlight -= job
      initiator.get ! JobResult.asQueryResult(results)

    case RequestQueuesSize =>
      sender() ! QueuesSize(mining = miningActiveActors.size,
                            experiments = experimentActiveActors.size)

    case Terminated(a) =>
      log.debug(s"Actor terminated: $a")
      miningActiveActors -= a
      experimentActiveActors -= a
      log.debug(s"Experiments active: ${experimentActiveActors.size}")

    case e =>
      log.warning(s"Received unhandled request $e of type ${e.getClass}")

  }

  private[api] def newExperimentActor: ActorRef =
    context.actorOf(ExperimentActor.props(coordinatorConfig))

  private[api] def newCoordinatorActor: ActorRef =
    context.actorOf(CoordinatorActor.props(coordinatorConfig))
}
