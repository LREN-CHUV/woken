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
import cats.implicits._
import ch.chuv.lren.woken.core.fp._
import ch.chuv.lren.woken.core.streams.debugElements
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.errors._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.validation.validationProtocol._
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.validation.flows.ValidationFlow
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }
import spray.json._

import scala.collection.immutable.TreeSet

object MiningQueriesActor extends LazyLogging {

  case class Mine(query: MiningQuery, replyTo: ActorRef)

  def props[F[_]: Effect](config: WokenConfiguration,
                          databaseServices: DatabaseServices[F],
                          backendServices: BackendServices[F]): Props =
    Props(
      new MiningQueriesActor(config, databaseServices, backendServices)
    )

  def roundRobinPoolProps[F[_]: Effect](config: WokenConfiguration,
                                        databaseServices: DatabaseServices[F],
                                        backendServices: BackendServices[F]): Props = {

    val resizer = OptimalSizeExploringResizer(
      config.config
        .getConfig("poolResizer.miningQueries")
        .withFallback(
          config.config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
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
      MiningQueriesActor.props(config, databaseServices, backendServices)
    )
  }

}

class MiningQueriesActor[F[_]: Effect](
    override val config: WokenConfiguration,
    override val databaseServices: DatabaseServices[F],
    override val backendServices: BackendServices[F]
) extends QueriesActor[MiningQuery, F] {

  import MiningQueriesActor.Mine

  private val validationFlow
    : Flow[ValidationJob[F], (ValidationJob[F], Either[String, Score]), NotUsed] =
    ValidationFlow(backendServices.wokenWorker).validate()

  @SuppressWarnings(
    Array("org.wartremover.warts.Any",
          "org.wartremover.warts.NonUnitStatements",
          "org.wartremover.warts.Product")
  )
  override def receive: Receive = {

    case mine: Mine =>
      val initiator = if (mine.replyTo == Actor.noSender) sender() else mine.replyTo
      // Define the target table if user did not specify it
      val query =
        if (mine.query.targetTable.isEmpty)
          mine.query.copy(targetTable = Some(config.jobs.featuresTable))
        else mine.query
      val jobValidatedF = queryToJobService.miningQuery2Job(query)
      val doIt: F[QueryResult] = jobValidatedF.flatMap { jv =>
        jv.fold(
          errList => {
            val errors = errList.mkString_(", ")
            val msg    = errorMsg(query, errors)
            errorMsgResult(query, msg, Set(), List()).pure[F]
          },
          j => processJob(query, j)
        )
      }

      runNow(doIt) {
        case Left(e) =>
          val msg = errorMsg(query, e.toString)
          logger.error(SKIP_REPORTING_MARKER, msg, e)
          val result = errorMsgResult(query, msg, Set(), List())
          backendServices.errorReporter.report(e, QueryError(result))
          initiator ! result

        case Right(results) =>
          results.error.foreach { error =>
            val msg = errorMsg(query, error)
            logger.error(SKIP_REPORTING_MARKER, msg)
            backendServices.errorReporter.report(new Exception(msg), QueryError(results))
          }
          logger.whenDebugEnabled {
            val feedbackMsg =
              if (results.feedback.nonEmpty) "with feedback " + results.feedback.mkString(", ")
              else ""
            results.data.foreach(
              _ => logger.debug(s"Mining for ${query.toJson.compactPrint} complete $feedbackMsg")
            )
          }
          initiator ! results
      }

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def errorMsg(query: MiningQuery, error: String) =
    s"Mining for ${query.toJson.compactPrint} failed with error: $error"

  private def processJob(query: MiningQuery, jobInProgress: MiningJobInProgress): F[QueryResult] = {
    val feedback = jobInProgress.feedback
    if (feedback.nonEmpty) logger.info(s"Feedback: ${feedback.mkString(", ")}")

    jobInProgress.job match {
      case job: MiningJob =>
        runMiningJob(jobInProgress, job)
      case job: ValidationJob[F] =>
        runValidationJob(jobInProgress, job)
      case job =>
        val msg = s"Unsupported job $job. Was expecting a job of type DockerJob or ValidationJob"
        errorMsgResult(query, msg, Set(), feedback).pure[F]
    }
  }

  private def runMiningJob(jobInProgress: MiningJobInProgress, job: MiningJob): F[QueryResult] = {

    val query = jobInProgress.job.query
    // Detection of histograms in federation mode
    val forceLocal = query.algorithm.code == "histograms"

    if (forceLocal) {
      logger.whenDebugEnabled(
        logger.debug(s"Local data mining for query ${query.toJson.compactPrint}")
      )
      startLocalMiningJob(jobInProgress, job)
    } else
      dispatcherService.dispatchTo(query.datasets) match {

        // Local mining on a worker node or a standalone node
        case (_, true) =>
          logger.whenDebugEnabled(
            logger.debug(s"Local data mining for query ${query.toJson.compactPrint}")
          )
          startLocalMiningJob(jobInProgress, job)

        // Mining from the central server using one remote node
        case (remoteLocations, false) if remoteLocations.size == 1 =>
          logger.whenDebugEnabled(
            logger.debug(
              s"Remote data mining on a single node $remoteLocations for query ${query.toJson.compactPrint}"
            )
          )
          remoteMapFlow(query)
            .mapAsync(parallelism = 1) {
              case List()        => Future(noResult(query, Set(), Nil))
              case List(result)  => Future(result.copy(query = Some(query)))
              case listOfResults => gatherAndReduce(query, listOfResults, None)
            }
            .log("Result of mining on remote node")
            .withAttributes(debugElements)
            .runWith(Sink.last)
            .fromFutureWithGuarantee(
              logErrorFinalizer(logger, s"Cannot complete mining job ${job.toString}")
            )

        // Execution of the algorithm from the central server in a distributed mode
        case (remoteLocations, _) =>
          logger.whenDebugEnabled(
            logger.debug(
              s"Remote data mining on nodes $remoteLocations for query ${query.toJson.compactPrint}"
            )
          )
          val algorithm           = job.dockerJob.algorithmSpec
          val algorithmDefinition = job.dockerJob.algorithmDefinition
          val queriesByStepExecution: Map[ExecutionStyle.Value, MiningQuery] =
            algorithmDefinition.distributedExecutionPlan.steps.map { step =>
              (step.execution,
               query.copy(covariablesMustExist = true,
                          algorithm = algorithm.copy(step = Some(step))))
            }.toMap

          val mapQuery = queriesByStepExecution.getOrElse(ExecutionStyle.map, query)
          val reduceQuery =
            queriesByStepExecution.get(ExecutionStyle.reduce).map(_.copy(datasets = TreeSet()))

          remoteMapFlow(mapQuery)
            .mapAsync(parallelism = 5) {
              case List(result) => Future(result.copy(query = Some(query)))
              case mapResults   => gatherAndReduce(query, mapResults, reduceQuery)
            }
            .runWith(Sink.last)
            .fromFutureWithGuarantee(
              logErrorFinalizer(logger, s"Cannot complete mining job ${job.toString}")
            )
      }
  }

  private def startLocalMiningJob(jobInProgress: MiningJobInProgress,
                                  job: MiningJob): F[QueryResult] = {
    val dockerJob = job.dockerJob
    val node      = config.jobs.node
    // TODO: add support for table schema
    val table = dockerJob.query.dbTable.name
    // TODO: use table content hash

    // Check the cache first
    databaseServices.resultsCacheService.get(node, table, None, job.query).flatMap[QueryResult] {
      resultO =>
        resultO.fold(doLocalMiningJob(jobInProgress, dockerJob)) { result =>
          logger.debug("Cache hit! Returning result")
          result.pure[F]
        }
    }
  }

  private def doLocalMiningJob(jobInProgress: MiningJobInProgress,
                               dockerJob: DockerJob): F[QueryResult] =
    backendServices.algorithmExecutor.execute(dockerJob).flatMap { r =>
      val query    = jobInProgress.job.query
      val prov     = jobInProgress.dataProvenance
      val feedback = jobInProgress.feedback

      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      val results = r.results match {
        case List()       => noResult(query, Set(), feedback)
        case List(result) => result.asQueryResult(Some(query), prov, feedback)
        case result :: _ =>
          val msg =
            s"Discarded additional results returned by algorithm ${dockerJob.algorithmDefinition.code}"
          logger.warn(msg)
          result.asQueryResult(Some(query), prov, feedback :+ UserWarning(msg))
      }

      results.error match {
        case None => databaseServices.resultsCacheService.put(results, query).map(_ => results)
        case _    => results.pure[F]
      }
    }

  private def remoteMapFlow(mapQuery: MiningQuery) =
    Source
      .single(mapQuery)
      .named("remote-map-operation")
      .via(dispatcherService.dispatchRemoteMiningFlow)
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }

  private def runValidationJob(jobInProgress: MiningJobInProgress,
                               job: ValidationJob[F]): F[QueryResult] = {
    val query    = jobInProgress.job.query
    val prov     = jobInProgress.dataProvenance
    val feedback = jobInProgress.feedback

    dispatcherService.dispatchTo(job.query.datasets) match {
      case (_, true) =>
        Source
          .single(job)
          .named("run-validation")
          .via(validationFlow)
          .runWith(Sink.last)
          .map {
            case (job: ValidationJob[F], Right(score)) =>
              QueryResult(
                Some(job.jobId),
                config.jobs.node,
                prov,
                feedback,
                OffsetDateTime.now(),
                Shapes.score,
                Some(ValidationJob.algorithmCode),
                Some(score.toJson),
                None,
                Some(query)
              )
            case (job: ValidationJob[F], Left(error)) =>
              QueryResult(
                Some(job.jobId),
                config.jobs.node,
                prov,
                feedback,
                OffsetDateTime.now(),
                Shapes.error,
                Some(ValidationJob.algorithmCode),
                None,
                Some(error),
                Some(query)
              )
          }
          .fromFutureWithGuarantee(
            logErrorFinalizer(logger, s"Cannot complete validation job ${job.toString}")
          )

      case _ =>
        logger.info(
          s"No local datasets match the validation query, asking for datasets ${job.query.datasets.mkString(",")}"
        )
        // No local datasets match the query, return an empty result
        QueryResult(
          Some(job.jobId),
          config.jobs.node,
          prov,
          feedback,
          OffsetDateTime.now(),
          Shapes.score,
          Some(ValidationJob.algorithmCode),
          None,
          None,
          Some(query)
        ).pure[F]
    }
  }

  private[dispatch] def reduceUsingJobs(query: MiningQuery, jobIds: List[String]): MiningQuery =
    query.copy(algorithm = addJobIds(query.algorithm, jobIds))

  override private[dispatch] def wrap(query: MiningQuery, initiator: ActorRef) =
    Mine(query, initiator)

}
