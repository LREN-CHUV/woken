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

import akka.NotUsed
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ Actor, ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.effect.Effect
import ch.chuv.lren.woken.core._
import ch.chuv.lren.woken.core.commands.JobCommands.StartExperimentJob
import ch.chuv.lren.woken.core.model.{
  AlgorithmDefinition,
  ErrorJobResult,
  ExperimentJobResult,
  JobResult
}
import ch.chuv.lren.woken.core.validation.RemoteValidationFlow
import ch.chuv.lren.woken.core.validation.RemoteValidationFlow.ValidationContext
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{ ExecutionStyle, _ }
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object ExperimentQueriesActor extends LazyLogging {

  case class Experiment(query: ExperimentQuery, replyTo: ActorRef)

  def props[F[_]: Effect](
      coordinatorConfig: CoordinatorConfig[F],
      dispatcherService: DispatcherService,
      experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job]
  ): Props =
    Props(
      new ExperimentQueriesActor(coordinatorConfig, dispatcherService, experimentQuery2JobF)
    )

  def roundRobinPoolProps[F[_]: Effect](
      config: Config,
      coordinatorConfig: CoordinatorConfig[F],
      dispatcherService: DispatcherService,
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
        .props(coordinatorConfig, dispatcherService, experimentQuery2JobF)
    )
  }

}

class ExperimentQueriesActor[F[_]: Effect](
    override val coordinatorConfig: CoordinatorConfig[F],
    dispatcherService: DispatcherService,
    experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job]
) extends QueriesActor[ExperimentQuery, F] {

  import ExperimentQueriesActor.Experiment

  val remoteValidationFlow: Flow[ValidationContext, ValidationContext, NotUsed] =
    RemoteValidationFlow(dispatcherService, context).remoteValidate

  override def receive: Receive = {

    case experiment: Experiment =>
      val initiator    = if (experiment.replyTo == Actor.noSender) sender() else experiment.replyTo
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
          initiator ! error.asQueryResult(Some(query))
        },
        job => runExperiment(query, initiator, job)
      )

    case ExperimentActor.Response(job, Left(results), initiator) =>
      logger.info(s"Received error response for experiment on ${job.query}: $results")
      initiator ! results.asQueryResult(Some(job.query))

    case ExperimentActor.Response(job, Right(results), initiator) =>
      logger.info(s"Received response for experiment on ${job.query}: $results")
      Source
        .single(ValidationContext(job.query, job.algorithms, results))
        .via(remoteValidationFlow)
        .map[QueryResult] { r =>
          val result = r.experimentResult.asQueryResult(Some(job.query))
          initiator ! result
          result
        }
        .recoverWithRetries[QueryResult](
          1, {
            case e: Exception =>
              val errorResult = reportError(job.query, initiator)(e)
              Source.single(errorResult)
          }
        )
        .log("Result of experiment")
        .runWith(Sink.last)
    //.runWith(Sink.actorRef(initiator, None)) -- nicer, but initiator may die on first message received

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def runExperiment(query: ExperimentQuery,
                            initiator: ActorRef,
                            job: ExperimentActor.Job): Unit =
    dispatcherService.dispatchTo(query.trainingDatasets) match {

      // Local execution of the experiment on a worker node or a standalone node
      case (_, true) =>
        logger.info(s"Local experiment for query $query")
        startExperimentJob(job, initiator)

      // Execution of the experiment from the central server using one remote node
      case (remoteLocations, false) if remoteLocations.size == 1 =>
        logger.info(s"Remote experiment on a single node $remoteLocations for query $query")
        mapFlow(job.query, job.algorithms)
          .mapAsync(1) {
            case List()        => Future(noResult(job.query))
            case List(result)  => Future(result.copy(query = Some(query)))
            case listOfResults => gatherAndReduce(query, listOfResults, None)
          }
          .map(reportResult(initiator))
          .log("Result of experiment")
          .runWith(Sink.last)
          .failed
          .foreach(reportError(query, initiator))

      // Execution of the experiment from the central server in a distributed mode
      case (remoteLocations, _) =>
        logger.info(s"Remote experiment on nodes $remoteLocations for query $query")
        val queriesByStepExecution: Map[ExecutionStyle.Value, ExperimentQuery] =
          job.query.algorithms
            .flatMap { algorithm =>
              val algorithmDefinition = job.definitionOf(algorithm)

              algorithmDefinition.distributedExecutionPlan.steps.map { step =>
                (step, algorithm.copy(step = Some(step)))
              }.toMap
            }
            .groupBy(_._1.execution)
            .map {
              case (step, algorithmSpecs) =>
                (step,
                 job.query.copy(covariablesMustExist = true, algorithms = algorithmSpecs.map(_._2)))
            }

        val mapQuery = queriesByStepExecution.getOrElse(ExecutionStyle.map, job.query)
        val reduceQuery = queriesByStepExecution
          .get(ExecutionStyle.reduce)
          .map(_.copy(trainingDatasets = Set(), validationDatasets = Set()))

        mapFlow(mapQuery, job.algorithms)
          .mapAsync(1) {
            case List()       => Future(noResult(query))
            case List(result) => Future(result.copy(query = Some(query)))
            case mapResults =>
              gatherAndReduce(query, mapResults, reduceQuery)
          }
          .map(reportResult(initiator))
          .log("Result of experiment")
          .runWith(Sink.last)
          .failed
          .foreach(reportError(query, initiator))
    }

  private def mapFlow(mapQuery: ExperimentQuery,
                      algorithms: Map[AlgorithmSpec, AlgorithmDefinition]) =
    Source
      .single(mapQuery)
      .via(dispatcherService.dispatchRemoteExperimentFlow)
      .mapAsync(10) { result =>
        JobResult.fromQueryResult(result._2) match {
          case experimentResult: ExperimentJobResult =>
            Source
              .single(ValidationContext(mapQuery, algorithms, experimentResult))
              .via(remoteValidationFlow)
              .map[QueryResult] { r =>
                r.experimentResult.asQueryResult(Some(mapQuery))
              }
              .recoverWithRetries[QueryResult](
                1, {
                  case e =>
                    logger.error("Cannot perform remote validation", e)
                    // TODO: the error message should be propagated to the final result, to inform the user
                    Source.single(result._2)
                }
              )
              .log("Result of experiment")
              .runWith(Sink.last)
          case r =>
            logger.warn(s"Expected an ExperimentJobResult, found $r")
            Future(result._2)
        }
      }
      .fold(List[QueryResult]()) {
        _ :+ _
      }

  private def startExperimentJob(job: ExperimentActor.Job, initiator: ActorRef): Unit = {
    val experimentActorRef = newExperimentActor
    experimentActorRef ! StartExperimentJob(job, self, initiator)
  }

  private[dispatch] def newExperimentActor: ActorRef =
    context.actorOf(ExperimentActor.props(coordinatorConfig, dispatcherService))

  private[dispatch] def reduceUsingJobs(query: ExperimentQuery,
                                        jobIds: List[String]): ExperimentQuery =
    query.copy(algorithms = query.algorithms.map(algorithm => addJobIds(algorithm, jobIds)))

  override private[dispatch] def wrap(query: ExperimentQuery, initiator: ActorRef) =
    Experiment(query, initiator)

}
