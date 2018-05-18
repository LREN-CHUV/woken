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
import cats.Applicative
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

trait QueriesActor extends Actor with LazyLogging {

  implicit val ec: ExecutionContext = context.dispatcher

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def coordinatorConfig: CoordinatorConfig

  private[dispatch] def gatherAndReduce(
      queryResults: List[QueryResult],
      reduceQuery: Option[Query]
  ): Future[QueryResult] = {

    import spray.json._
    import queryProtocol._

    val jobIdsToReduce: Option[List[String]] = reduceQuery
      .map(algorithmsOfQuery)
      .map { algorithms: List[AlgorithmSpec] =>
        queryResults
        // TODO: cannot support a case where the same algorithm is used, but with different execution plans
          .filter(r => r.algorithm.fold(false)(rAlgo => algorithms.exists(_.code == rAlgo)))
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
      }

    val resultsToCompoundGather = queryResults.filter { result =>
      reduceQuery.exists { query =>
        result.algorithm.exists(code => algorithmsOfQuery(query).exists(_.code == code))
      }
    }

    Applicative[Option]
      .map2(reduceQuery, jobIdsToReduce)((_, _))
      .map {
        case (query: MiningQuery, jobIds) =>
          query.copy(algorithm = addJobIds(query.algorithm, jobIds))
        case (query: ExperimentQuery, jobIds) =>
          query.copy(algorithms = query.algorithms.map(algorithm => addJobIds(algorithm, jobIds)))
      }
      .fold(Future(resultsToCompoundGather)) { query =>
        implicit val askTimeout: Timeout = Timeout(60 minutes)
        (self ? query)
          .mapTo[QueryResult]
          .map { reducedResult =>
            resultsToCompoundGather :+ reducedResult
          }
      }
      .map {
        case Nil          => noResult()
        case List(result) => result
        case results =>
          QueryResult(
            jobId = None,
            node = coordinatorConfig.jobsConf.node,
            timestamp = OffsetDateTime.now(),
            `type` = Shapes.compound,
            algorithm = None,
            data = Some(results.toJson),
            error = None
          )
      }

  }

  private[dispatch] def noResult(): QueryResult =
    ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, "No results").asQueryResult

  private[dispatch] def reportResult(initiator: ActorRef)(queryResult: QueryResult): QueryResult = {
    initiator ! queryResult
    queryResult
  }

  private[dispatch] def reportError(initiator: ActorRef)(e: Throwable): Unit = {
    logger.error(s"Cannot complete query because of ${e.getMessage}", e)
    val error =
      ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, e.toString)
    initiator ! error.asQueryResult
  }

  private[dispatch] def reportError(query: Query, initiator: ActorRef)(e: Throwable): Unit = {
    logger.error(s"Cannot complete query $query", e)
    val error =
      ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, e.toString)
    initiator ! error.asQueryResult
  }

  private[dispatch] def reportErrorMessage(query: Query, initiator: ActorRef)(errorMessage: String): Unit = {
    logger.error(s"Cannot complete query $query, cause $errorMessage")
    val error =
      ErrorJobResult(None, coordinatorConfig.jobsConf.node, OffsetDateTime.now(), None, errorMessage)
    initiator ! error.asQueryResult
  }

  private[dispatch] def algorithmsOfQuery(query: Query): List[AlgorithmSpec] = query match {
    case q: MiningQuery     => List(q.algorithm)
    case q: ExperimentQuery => q.algorithms
  }

  private[dispatch] def addJobIds(algorithmSpec: AlgorithmSpec,
                                  jobIds: List[String]): AlgorithmSpec =
    algorithmSpec.copy(
      parameters = algorithmSpec.parameters :+ CodeValue("_job_ids_", jobIds.mkString(","))
    )

}
