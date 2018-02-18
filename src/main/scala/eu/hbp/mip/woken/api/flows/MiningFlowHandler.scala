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
import cats.data.{ NonEmptyList, Validated }
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.AppConfiguration
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig }
import eu.hbp.mip.woken.core.commands.JobCommands.StartCoordinatorJob
import eu.hbp.mip.woken.core.model.ErrorJobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.messages.query.{ MiningQuery, QueryResult }
import eu.hbp.mip.woken.service.DispatcherService

class MiningFlowHandler(
    appConfiguration: AppConfiguration,
    coordinatorConfig: CoordinatorConfig,
    dispatcherService: DispatcherService,
    miningQuery2JobF: MiningQuery => Validation[DockerJob]
)(implicit system: ActorSystem)
    extends FlowHandler {

  val miningActiveActorsLimit: Int                 = appConfiguration.masterRouterConfig.miningActorsLimit
  var miningJobsInFlight: Map[DockerJob, ActorRef] = Map()

  private def canProcessJob(query: MiningQuery): Boolean =
    miningJobsInFlight.size <= miningActiveActorsLimit

  private def canBuildValidJob(
      query: MiningQuery
  ): Validated[NonEmptyList[String], (DockerJob, MiningQuery)] =
    miningQuery2JobF(query).map(job => (job, query))

  private val validationFailedFlow
    : Flow[Validated[NonEmptyList[String], (DockerJob, MiningQuery)], QueryResult, _] =
    Flow[Validated[NonEmptyList[String], (DockerJob, MiningQuery)]].map { result =>
      val errorMsg = result.toEither.left.get
      ErrorJobResult("",
                     coordinatorConfig.jobsConf.node,
                     OffsetDateTime.now(),
                     "mining query",
                     errorMsg.reduceLeft(_ + ", " + _)).asQueryResult
    }

  private def isNewJobRequired(validatedJob: ValidatedMiningJob): Boolean =
    dispatcherService.dispatchTo(validatedJob._2.datasets) match {
      case (_, true) => true
      case _         => false
    }

  private val tooBusyFlow: Flow[MiningQuery, QueryResult, _] =
    Flow[MiningQuery].map(
      _ =>
        ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.").asQueryResult
    )

  val startMiningTask: Flow[ValidatedMiningJob, QueryResult, _] =
    Flow[ValidatedMiningJob].map(startMiningJob)

  val executionFlow: Flow[ValidatedMiningJob, QueryResult, _] =
    Flow[ValidatedMiningJob]
      .map(_._2)
      .via(dispatcherService.remoteDispatchMiningFlow())
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }
      .map {
        case List() =>
          ErrorJobResult("",
                         coordinatorConfig.jobsConf.node,
                         OffsetDateTime.now(),
                         "",
                         "No results").asQueryResult

        case List(result) => result

        case listOfResults =>
          compoundResult(coordinatorConfig, listOfResults)
      }

  private def shouldStartNewJob
    : Flow[Validated[NonEmptyList[String], (DockerJob, MiningQuery)], QueryResult, NotUsed] =
    Flow[Validated[NonEmptyList[String], (DockerJob, MiningQuery)]]
      .map(_.toOption.get)
      .via(conditionalFlow(isNewJobRequired, startMiningTask, executionFlow))

  val miningFlow: Flow[MiningQuery, QueryResult, _] =
    Flow[MiningQuery]
      .via(
        conditionalFlow(
          canProcessJob,
          validationFlow(
            canBuildValidJob,
            validationFailedFlow,
            shouldStartNewJob
          ),
          tooBusyFlow
        )
      )

  private def startMiningJob(validatedJob: ValidatedMiningJob): QueryResult = {
    val job            = validatedJob._1
    val miningActorRef = newCoordinatorActor
    miningActorRef ! StartCoordinatorJob(job)
    miningJobsInFlight += job -> miningActorRef
    // TODO: need to fix the data included in return type
    QueryResult(job.jobId, "", OffsetDateTime.now(), "", "", None, None)
  }

  private[api] def newCoordinatorActor: ActorRef = {
    val ref = system.actorOf(CoordinatorActor.props(coordinatorConfig))
    // context watch ref
    ref
  }

}
