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

import akka.actor.{ Actor, ActorRef }
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import ch.chuv.lren.woken.core.CoordinatorConfig
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, ExperimentJobResult, JobResult }
import ch.chuv.lren.woken.messages.query._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

trait QueriesActor[Q <: Query] extends Actor with LazyLogging {

  implicit val ec: ExecutionContext = context.dispatcher

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def coordinatorConfig: CoordinatorConfig

  private[dispatch] def gatherAndReduce(
      initialQuery: Q,
      mapQueryResults: List[QueryResult],
      reduceQuery: Option[Q]
  ): Future[QueryResult] = {

    import spray.json._
    import queryProtocol._

    logger.info(s"Reduce query is $reduceQuery")

    // Select in the results the results that match the reduce query
    val resultsCandidateForReduce: List[QueryResult] = reduceQuery
      .map(algorithmsOfQuery)
      .fold(List[QueryResult]()) { algorithms: List[AlgorithmSpec] =>
        mapQueryResults
        // TODO: cannot support a case where the same algorithm is used, but with different execution plans
          .filter { r =>
            logger.info(
              s"Check that result for algorithm ${r.algorithm} is in the reduce query algorithms ${algorithms.map(_.code).mkString(",")}"
            )
            r.algorithm.fold(false)(rAlgo => algorithms.exists(_.code == rAlgo))
          }
      }

    logger.info(
      s"Select ${resultsCandidateForReduce.size} results out of ${mapQueryResults.size} for reduce operation"
    )

    val jobIdsToReduce: List[String] = resultsCandidateForReduce
      .flatMap { queryResult =>
        // With side effect: store results in the Jobs database for consumption by the algorithms
        JobResult.fromQueryResult(queryResult) match {
          case experiment: ExperimentJobResult =>
            experiment.results.valuesIterator.map { jobResult =>
              coordinatorConfig.jobResultService.put(jobResult)
              jobResult.jobIdM.getOrElse("")
            }.toList
          case jobResult =>
            coordinatorConfig.jobResultService.put(jobResult)
            List(jobResult.jobIdM.getOrElse(""))
        }
      }
      .filter(_.nonEmpty)

    logger.info(s"Selected job ids ${jobIdsToReduce.mkString(",")} for reduce operation")

    val resultsToCompoundGather = mapQueryResults.diff(resultsCandidateForReduce)

    reduceQuery
      .map { query =>
        reduceUsingJobs(query, jobIdsToReduce)
      }
      .fold(Future(resultsToCompoundGather)) { query =>
        implicit val askTimeout: Timeout = Timeout(60 minutes)
        (self ? wrap(query, Actor.noSender))
          .mapTo[QueryResult]
          .map { reducedResult =>
            resultsToCompoundGather :+ reducedResult
          }
      }
      .map {
        case Nil          => noResult(initialQuery)
        case List(result) => result.copy(query = Some(initialQuery))
        case results =>
          QueryResult(
            jobId = None,
            node = coordinatorConfig.jobsConf.node,
            timestamp = OffsetDateTime.now(),
            `type` = Shapes.compound,
            algorithm = None,
            data = Some(results.toJson),
            error = None,
            query = Some(initialQuery)
          )
      }

  }

  private[dispatch] def noResult(initialQuery: Q): QueryResult =
    ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, "No results")
      .asQueryResult(Some(initialQuery))

  private[dispatch] def reportResult(initiator: ActorRef)(queryResult: QueryResult): QueryResult = {
    initiator ! queryResult
    queryResult
  }

  private[dispatch] def reportError(initialQuery: Q,
                                    initiator: ActorRef)(e: Throwable): QueryResult = {
    logger.error(s"Cannot complete query because of ${e.getMessage}", e)
    val error =
      ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, e.toString)
        .asQueryResult(Some(initialQuery))
    initiator ! error
    error
  }

  private[dispatch] def reportErrorMessage(initialQuery: Q,
                                           initiator: ActorRef)(errorMessage: String): Unit = {
    logger.error(s"Cannot complete query $initialQuery, cause $errorMessage")
    val error =
      ErrorJobResult(None,
                     coordinatorConfig.jobsConf.node,
                     OffsetDateTime.now(),
                     None,
                     errorMessage)
    initiator ! error.asQueryResult(Some(initialQuery))
  }

  private[dispatch] def algorithmsOfQuery(query: Q): List[AlgorithmSpec] = query match {
    case q: MiningQuery     => List(q.algorithm)
    case q: ExperimentQuery => q.algorithms
  }

  private[dispatch] def reduceUsingJobs(query: Q, jobIds: List[String]): Q

  private[dispatch] def addJobIds(algorithm: AlgorithmSpec, jobIds: List[String]): AlgorithmSpec =
    algorithm.copy(
      parameters = algorithm.parameters :+ CodeValue("_job_ids_", jobIds.mkString(","))
    )

  private[dispatch] def wrap(query: Q, initiator: ActorRef): Any

}
