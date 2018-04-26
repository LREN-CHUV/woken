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

package ch.chuv.lren.woken.dispatch

import java.time.OffsetDateTime

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core._
import ch.chuv.lren.woken.core.commands.JobCommands.StartExperimentJob
import ch.chuv.lren.woken.core.model.ErrorJobResult
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, QueryResult }
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object ExperimentQueriesActor extends LazyLogging {

  case class Experiment(query: ExperimentQuery, replyTo: ActorRef)

  def props(coordinatorConfig: CoordinatorConfig,
            dispatcherService: DispatcherService,
            algorithmLookup: String => Validation[AlgorithmDefinition],
            experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job]): Props =
    Props(
      new ExperimentQueriesActor(coordinatorConfig,
                                 dispatcherService,
                                 algorithmLookup,
                                 experimentQuery2JobF)
    )

  def roundRobinPoolProps(
      config: Config,
      coordinatorConfig: CoordinatorConfig,
      dispatcherService: DispatcherService,
      algorithmLookup: String => Validation[AlgorithmDefinition],
      experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job]
  ): Props = {

    val resizer = OptimalSizeExploringResizer(
      config
        .getConfig("poolResizer.experimentQueries")
        .withFallback(
          config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val experimentSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case e: Exception =>
          logger.error("Error detected in Experiment queries actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = experimentSupervisorStrategy
    ).props(
      ExperimentQueriesActor
        .props(coordinatorConfig, dispatcherService, algorithmLookup, experimentQuery2JobF)
    )
  }

}

class ExperimentQueriesActor(
    override val coordinatorConfig: CoordinatorConfig,
    dispatcherService: DispatcherService,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job]
) extends QueriesActor
    with LazyLogging {

  import ExperimentQueriesActor.Experiment

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  override def receive: Receive = {

    case experiment: Experiment =>
      val initiator    = experiment.replyTo
      val query        = experiment.query
      val jobValidated = experimentQuery2JobF(query)

      jobValidated.fold(
        errorMsg => {
          val error =
            ErrorJobResult(
              None,
              coordinatorConfig.jobsConf.node,
              OffsetDateTime.now(),
              None,
              s"Experiment $query failed with message: " + errorMsg.reduceLeft(_ + ", " + _)
            )
          initiator ! error.asQueryResult
        },
        job => runExperiment(query, initiator, job)
      )

    case ExperimentActor.Response(job, Left(results), initiator) =>
      logger.info(s"Received error response for experiment on ${job.query}: $results")
      initiator ! results.asQueryResult

    case ExperimentActor.Response(job, Right(results), initiator) =>
      logger.info(s"Received response for experiment on ${job.query}: $results")
      initiator ! results.asQueryResult

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def runExperiment(query: ExperimentQuery,
                            initiator: ActorRef,
                            job: ExperimentActor.Job): Unit =
    dispatcherService.dispatchTo(query.trainingDatasets) match {
      case (_, true) => startExperimentJob(job, initiator)
      case _ =>
        logger.info("Dispatch experiment query to remote workers...")

        Source
          .single(query)
          .via(dispatcherService.dispatchRemoteExperimentFlow)
          .fold(List[QueryResult]()) {
            _ :+ _._2
          }
          .map {
            case List() =>
              ErrorJobResult(None,
                             coordinatorConfig.jobsConf.node,
                             OffsetDateTime.now(),
                             None,
                             "No results").asQueryResult

            case List(result) => result

            case listOfResults =>
              compoundResult(listOfResults)
          }
          .map { queryResult =>
            initiator ! queryResult
            queryResult
          }
          .log("Result of experiment")
          .runWith(Sink.last)
          .failed
          .foreach { e =>
            logger.error(s"Cannot complete experiment query $query", e)
            val error =
              ErrorJobResult(None,
                             coordinatorConfig.jobsConf.node,
                             OffsetDateTime.now(),
                             None,
                             e.toString)
            initiator ! error.asQueryResult
          }
    }

  private def startExperimentJob(job: ExperimentActor.Job, initiator: ActorRef): Unit = {
    val experimentActorRef = newExperimentActor
    experimentActorRef ! StartExperimentJob(job, self, initiator)
  }

  private[dispatch] def newExperimentActor: ActorRef =
    context.actorOf(ExperimentActor.props(coordinatorConfig, algorithmLookup))

}
