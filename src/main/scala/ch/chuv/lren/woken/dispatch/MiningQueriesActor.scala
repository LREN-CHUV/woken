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
import akka.actor.{ ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core._
import ch.chuv.lren.woken.core.commands.JobCommands.StartCoordinatorJob
import ch.chuv.lren.woken.core.model.{ DockerJob, ErrorJobResult, Job, ValidationJob }
import ch.chuv.lren.woken.core.validation.ValidationFlow
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{ ExecutionStyle, MiningQuery, QueryResult, Shapes }
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.validation.validationProtocol._
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{ Failure, Success }
import spray.json._

object MiningQueriesActor extends LazyLogging {

  case class Mine(query: MiningQuery, replyTo: ActorRef)

  def props(coordinatorConfig: CoordinatorConfig,
            dispatcherService: DispatcherService,
            algorithmLookup: String => Validation[AlgorithmDefinition],
            miningQuery2JobF: MiningQuery => Validation[Job]): Props =
    Props(
      new MiningQueriesActor(coordinatorConfig,
                             dispatcherService,
                             algorithmLookup,
                             miningQuery2JobF)
    )

  def roundRobinPoolProps(config: Config,
                          coordinatorConfig: CoordinatorConfig,
                          dispatcherService: DispatcherService,
                          algorithmLookup: String => Validation[AlgorithmDefinition],
                          miningQuery2JobF: MiningQuery => Validation[Job]): Props = {

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
    ).props(
      MiningQueriesActor
        .props(coordinatorConfig, dispatcherService, algorithmLookup, miningQuery2JobF)
    )
  }

}

class MiningQueriesActor(
    override val coordinatorConfig: CoordinatorConfig,
    dispatcherService: DispatcherService,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    miningQuery2JobF: MiningQuery => Validation[Job]
) extends QueriesActor {

  import MiningQueriesActor.Mine

  private val validationFlow: Flow[ValidationJob, (ValidationJob, Either[String, Score]), NotUsed] =
    ValidationFlow(
      CoordinatorActor.executeJobAsync(coordinatorConfig, context),
      coordinatorConfig.featuresDatabase,
      context
    ).validate()

  @SuppressWarnings(
    Array("org.wartremover.warts.Any",
          "org.wartremover.warts.NonUnitStatements",
          "org.wartremover.warts.Product")
  )
  override def receive: Receive = {

    case mine: Mine =>
      val initiator    = mine.replyTo
      val query        = mine.query
      val jobValidated = miningQuery2JobF(query)

      jobValidated.fold( errorMsg => {
          reportErrorMessage(query, initiator)(s"Mining for $query failed with message: " + errorMsg.reduceLeft(_ + ", " + _))
        }, {
          case job: DockerJob     => runMiningJob(query, initiator, job)
          case job: ValidationJob => runValidationJob(initiator, job)
          case job => reportErrorMessage(query, initiator)(s"Unsupported job $job. Was expecting a job of type DockerJob or ValidationJob")
        }
      )

    case CoordinatorActor.Response(job, List(errorJob: ErrorJobResult), initiator) =>
      logger.warn(s"Received error while mining ${job.query}: ${errorJob.toString}")
      initiator ! errorJob.asQueryResult

    case CoordinatorActor.Response(job, results, initiator) =>
      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      logger.info(s"Received results for mining ${job.query}: $results")
      val jobResult = results.head
      initiator ! jobResult.asQueryResult

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def runMiningJob(query: MiningQuery, initiator: ActorRef, job: DockerJob): Unit =
    dispatcherService.dispatchTo(query.datasets) match {

      // Local mining on a worker node or a standalone node
      case (_, true) =>
        logger.info(s"Local data mining for query $query")
        startMiningJob(job, initiator)

      // Mining from the central server using one remote node
      case (remoteLocations, false) if remoteLocations.size == 1 =>
        logger.info(s"Remote data mining on a single node $remoteLocations for query $query")
        mapFlow(query)
          .mapAsync(1) {
            case List()        => Future(noResult())
            case List(result)  => Future(result)
            case listOfResults => gatherAndReduce(listOfResults, None)
          }
          .map(reportResult(initiator))
          .log("Result of experiment")
          .runWith(Sink.last)
          .failed
          .foreach(reportError(query, initiator))

      // Execution of the experiment from the central server in a distributed mode
      case (remoteLocations, _) =>
        logger.info(s"Remote data mining on nodes $remoteLocations for query $query")
        val algorithm = job.algorithmSpec
        val algorithmDefinition: AlgorithmDefinition = algorithmLookup(algorithm.code)
          .valueOr(e => throw new IllegalStateException(e.toList.mkString(",")))
        val queriesByStepExecution: Map[ExecutionStyle.Value, MiningQuery] =
          algorithmDefinition.distributedExecutionPlan.steps
            .map { step =>
              (step, algorithm.copy(step = Some(step)))
            }
            .map {
              case (step, algorithmSpec) =>
                (step.execution, query.copy(algorithm = algorithmSpec))
            }
            .toMap

        val mapQuery    = queriesByStepExecution.getOrElse(ExecutionStyle.map, query)
        val reduceQuery = queriesByStepExecution.get(ExecutionStyle.reduce)

        mapFlow(mapQuery)
          .mapAsync(1) {
            case List()        => Future(noResult())
            case List(result)  => Future(result)
            case listOfResults => gatherAndReduce(listOfResults, reduceQuery)
          }
          .map(reportResult(initiator))
          .runWith(Sink.last)
          .failed
          .foreach(reportError(query, initiator))
    }

  private def mapFlow(mapQuery: MiningQuery) =
    Source
      .single(mapQuery)
      .via(dispatcherService.dispatchRemoteMiningFlow)
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }

  private def startMiningJob(job: DockerJob, initiator: ActorRef): Unit = {
    val miningActorRef = newCoordinatorActor
    miningActorRef ! StartCoordinatorJob(job, self, initiator)
  }

  private[dispatch] def newCoordinatorActor: ActorRef =
    context.actorOf(CoordinatorActor.props(coordinatorConfig))

  private def runValidationJob(initiator: ActorRef, job: ValidationJob): Unit =
    dispatcherService.dispatchTo(job.query.datasets) match {
      case (_, true) =>
        Source
          .single(job)
          .via(validationFlow)
          .runWith(Sink.last)
          .andThen {
            case Success((job: ValidationJob, Right(score))) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.score,
                Some(ValidationJob.algorithmCode),
                Some(score.toJson),
                None
              )
            case Success((job: ValidationJob, Left(error))) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.error,
                Some(ValidationJob.algorithmCode),
                None,
                Some(error)
              )
            case Failure(t) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.error,
                Some(ValidationJob.algorithmCode),
                None,
                Some(t.toString)
              )

          }

      case _ =>
        logger.info(
          s"No local datasets match the validation query, asking for datasets ${job.query.datasets.mkString(",")}"
        )
        // No local datasets match the query, return an empty result
        initiator ! QueryResult(
          Some(job.jobId),
          coordinatorConfig.jobsConf.node,
          OffsetDateTime.now(),
          Shapes.score,
          Some(ValidationJob.algorithmCode),
          None,
          None
        )
    }

}
