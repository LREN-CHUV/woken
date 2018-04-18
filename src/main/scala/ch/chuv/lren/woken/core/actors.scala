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

import java.time.OffsetDateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, OneForOneStrategy, Props }
import akka.actor.SupervisorStrategy.Restart
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.commands.JobCommands.{ StartCoordinatorJob, StartExperimentJob }
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, Shapes }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{
  ExperimentQuery,
  MiningQuery,
  QueryResult,
  queryProtocol
}
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object MiningQueriesActor extends LazyLogging {

  case class Mine(query: MiningQuery, replyTo: ActorRef)

  def props(coordinatorConfig: CoordinatorConfig,
            dispatcherService: DispatcherService,
            miningQuery2JobF: MiningQuery => Validation[DockerJob]): Props =
    Props(new MiningQueriesActor(coordinatorConfig, dispatcherService, miningQuery2JobF))

  def roundRobinPoolProps(config: Config,
                          coordinatorConfig: CoordinatorConfig,
                          dispatcherService: DispatcherService,
                          miningQuery2JobF: MiningQuery => Validation[DockerJob]): Props = {

    val resizer = OptimalSizeExploringResizer(
      config
        .getConfig("poolResizer.miningQueries")
        .withFallback(
          config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val miningSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case e: Exception =>
          logger.error("Error detected in Mining queries actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = miningSupervisorStrategy
    ).props(MiningQueriesActor.props(coordinatorConfig, dispatcherService, miningQuery2JobF))
  }

}

trait QueriesActor extends Actor {
  def coordinatorConfig: CoordinatorConfig

  private[core] def compoundResult(queryResults: List[QueryResult]): QueryResult = {
    import spray.json._
    import queryProtocol._

    QueryResult(
      jobId = "",
      node = coordinatorConfig.jobsConf.node,
      timestamp = OffsetDateTime.now(),
      shape = Shapes.compound.mime,
      algorithm = "compound",
      data = Some(queryResults.toJson),
      error = None
    )
  }

}

class MiningQueriesActor(
    override val coordinatorConfig: CoordinatorConfig,
    dispatcherService: DispatcherService,
    miningQuery2JobF: MiningQuery => Validation[DockerJob]
) extends QueriesActor
    with ActorLogging {

  import MiningQueriesActor.Mine

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  override def receive: Receive = {

    case mine: Mine =>
      val initiator    = mine.replyTo
      val query        = mine.query
      val jobValidated = miningQuery2JobF(query)

      jobValidated.fold(
        errorMsg => {
          val error =
            ErrorJobResult("",
                           coordinatorConfig.jobsConf.node,
                           OffsetDateTime.now(),
                           query.algorithm.code,
                           errorMsg.reduceLeft(_ + ", " + _))
          initiator ! error.asQueryResult
        },
        job => runMiningJob(query, initiator, job)
      )

    case CoordinatorActor.Response(job, List(errorJob: ErrorJobResult), initiator) =>
      log.warning(s"Received error while mining ${job.query}: $errorJob")
      initiator ! errorJob.asQueryResult

    case CoordinatorActor.Response(job, results, initiator) =>
      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      log.info(s"Received results for mining ${job.query}: $results")
      val jobResult = results.head
      initiator ! jobResult.asQueryResult

    case e =>
      log.warning(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def runMiningJob(query: MiningQuery, initiator: ActorRef, job: DockerJob): Unit =
    dispatcherService.dispatchTo(query.datasets) match {
      case (_, true) => startMiningJob(job, initiator)
      case _ =>
        log.info("Dispatch mining query to remote workers...")

        Source
          .single(query)
          .via(dispatcherService.dispatchRemoteMiningFlow)
          .fold(List[QueryResult]()) {
            _ :+ _._2
          }
          .map {
            case List() =>
              ErrorJobResult("",
                             coordinatorConfig.jobsConf.node,
                             OffsetDateTime.now(),
                             query.algorithm.code,
                             "No results").asQueryResult

            case List(result) => result

            case listOfResults =>
              compoundResult(listOfResults)
          }
          .map { queryResult: QueryResult =>
            initiator ! queryResult
            queryResult
          }
          .runWith(Sink.last)
          .failed
          .foreach { e =>
            log.error(e, s"Cannot complete mining query $query")
            val error =
              ErrorJobResult("", "", OffsetDateTime.now(), "experiment", e.toString)
            initiator ! error.asQueryResult
          }
    }

  private def startMiningJob(job: DockerJob, initiator: ActorRef): Unit = {
    val miningActorRef = newCoordinatorActor
    miningActorRef ! StartCoordinatorJob(job, self, initiator)
  }

  private[core] def newCoordinatorActor: ActorRef = {
    val ref = context.actorOf(CoordinatorActor.props(coordinatorConfig))
    context watch ref
    ref
  }

}

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
    with ActorLogging {

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
            ErrorJobResult("",
                           coordinatorConfig.jobsConf.node,
                           OffsetDateTime.now(),
                           "experiment",
                           errorMsg.reduceLeft(_ + ", " + _))
          initiator ! error.asQueryResult
        },
        job => runExperiment(query, initiator, job)
      )

    case ExperimentActor.Response(job, Left(results), initiator) =>
      log.info(s"Received experiment error response $results")
      initiator ! results.asQueryResult

    case ExperimentActor.Response(job, Right(results), initiator) =>
      log.info(s"Received experiment response $results")
      initiator ! results.asQueryResult

  }

  private def runExperiment(query: ExperimentQuery,
                            initiator: ActorRef,
                            job: ExperimentActor.Job): Unit =
    dispatcherService.dispatchTo(query.trainingDatasets) match {
      case (_, true) => startExperimentJob(job, initiator)
      case _ =>
        log.info("Dispatch experiment query to remote workers...")

        Source
          .single(query)
          .via(dispatcherService.dispatchRemoteExperimentFlow)
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
              compoundResult(listOfResults)
          }
          .map { queryResult =>
            initiator ! queryResult
            queryResult
          }
          .runWith(Sink.last)
          .failed
          .foreach { e =>
            log.error(e, s"Cannot complete experiment query $query")
            val error =
              ErrorJobResult("", "", OffsetDateTime.now(), "experiment", e.toString)
            initiator ! error.asQueryResult
          }
    }

  private def startExperimentJob(job: ExperimentActor.Job, initiator: ActorRef): Unit = {
    val experimentActorRef = newExperimentActor
    experimentActorRef ! StartExperimentJob(job, self, initiator)
  }

  private[core] def newExperimentActor: ActorRef = {
    val ref = context.actorOf(ExperimentActor.props(coordinatorConfig, algorithmLookup))
    context watch ref
    ref
  }

}
