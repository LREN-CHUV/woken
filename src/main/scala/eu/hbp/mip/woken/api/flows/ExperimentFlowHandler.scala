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

package eu.hbp.mip.woken.api.flows

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.scaladsl.Flow
import eu.hbp.mip.woken.config.{ AlgorithmDefinition, AppConfiguration }
import eu.hbp.mip.woken.core.commands.JobCommands.StartExperimentJob
import eu.hbp.mip.woken.core.{ CoordinatorConfig, ExperimentActor }
import eu.hbp.mip.woken.core.model.ErrorJobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.messages.query.{ ExperimentQuery, QueryResult }
import eu.hbp.mip.woken.service.DispatcherService

/**
  * Experiment Flow.
  */
class ExperimentFlowHandler(
    appConfiguration: AppConfiguration,
    experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job],
    dispatcherService: DispatcherService,
    coordinatorConfig: CoordinatorConfig,
    algorithmLookup: String => Validation[AlgorithmDefinition]
)(implicit system: ActorSystem)
    extends FlowHandler {

  private val experimentActiveActorsLimit: Int =
    appConfiguration.masterRouterConfig.miningActorsLimit
  private var experimentJobsInFlight: Map[ExperimentActor.Job, ActorRef] = Map()

  private def canProcessJob(query: ExperimentQuery): Boolean =
    experimentJobsInFlight.size <= experimentActiveActorsLimit

  private def canBuildValidJob(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    experimentQuery2JobF(query)

  private val validationFailedFlow: Flow[Validation[ExperimentActor.Job], QueryResult, _] =
    Flow[Validation[ExperimentActor.Job]].map { errorMsg =>
      ErrorJobResult("",
                     "",
                     OffsetDateTime.now(),
                     "experiment",
                     errorMsg.toEither.left.get.reduceLeft(_ + ", " + _)).asQueryResult
    }
  private val tooBusyFlow: Flow[ExperimentQuery, QueryResult, _] = Flow[ExperimentQuery].map(
    _ =>
      ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.").asQueryResult
  )

  private def isNewJobRequired(job: ExperimentActor.Job): Boolean =
    dispatcherService.dispatchTo(job.query.trainingDatasets) match {
      case (_, true) => true
      case _         => false
    }

  private val executionFlow: Flow[ExperimentActor.Job, QueryResult, NotUsed] =
    Flow[ExperimentActor.Job]
      .map { job =>
        job.query
      }
      .via(dispatcherService.remoteDispatchExperimentFlow())
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }
      .map {
        case List() =>
          ErrorJobResult("",
                         coordinatorConfig.jobsConf.node,
                         OffsetDateTime.now(),
                         "experiment",
                         "No results").asQueryResult

        case List(result) => result

        case listOfResults =>
          compoundResult(coordinatorConfig, listOfResults)
      }

  private val startExperimentTask: Flow[ExperimentActor.Job, QueryResult, NotUsed] =
    Flow[ExperimentActor.Job].map(startExperimentJob)

  private val shouldStartANewJob: Flow[Validation[ExperimentActor.Job], QueryResult, NotUsed] =
    Flow[Validation[ExperimentActor.Job]]
      .map(job => job.toOption.get)
      .via(conditionalFlow(isNewJobRequired, startExperimentTask, executionFlow))

  val experimentFlow: Flow[ExperimentQuery, QueryResult, _] =
    Flow[ExperimentQuery]
      .via(
        conditionalFlow(
          canProcessJob,
          validationFlow(
            canBuildValidJob,
            validationFailedFlow,
            shouldStartANewJob
          ),
          tooBusyFlow
        )
      )

  private def startExperimentJob(job: ExperimentActor.Job): QueryResult = {
    val experimentActorRef = newExperimentActor
    experimentActorRef ! StartExperimentJob(job)
    experimentJobsInFlight += job -> experimentActorRef
    // TODO: need to fix the data included in return type
    QueryResult(job.jobId, "", OffsetDateTime.now(), "", "", None, None)
  }

  private[api] def newExperimentActor: ActorRef = {
    val ref = system.actorOf(ExperimentActor.props(coordinatorConfig, algorithmLookup))
    //TODO: need some supervision for experiment actor
    //context watch ref
    ref
  }

}
