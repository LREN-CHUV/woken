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
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.jobs.{ ErrorJobResult, ExperimentJobResult, JobResult }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{
  BackendServices,
  DatabaseServices,
  DispatcherService,
  QueryToJobService
}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }
import scala.util.control.NonFatal

trait QueriesActor[Q <: Query, F[_]] extends Actor with LazyLogging {

  implicit val ec: ExecutionContext = context.dispatcher

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def config: WokenConfiguration
  def databaseServices: DatabaseServices[F]
  def backendServices: BackendServices[F]

  protected def queryToJobService: QueryToJobService[F] = databaseServices.queryToJobService
  protected def dispatcherService: DispatcherService    = backendServices.dispatcherService

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
              s"Check that algorithms in query ${algorithmsOfQuery(r.query)} are in the reduce query algorithms ${algorithms.map(_.toString).mkString(",")}"
            )
            r.query match {
              case q: MiningQuery => algorithms.exists(_.code == q.algorithm.code)
              case q: ExperimentQuery =>
                q.algorithms.exists { qAlgorithm =>
                  algorithms.exists(_.code == qAlgorithm.code)
                }
            }
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
              databaseServices.jobResultService.put(jobResult)
              jobResult.jobIdM.getOrElse("")
            }.toList
          case jobResult =>
            databaseServices.jobResultService.put(jobResult)
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
          .recoverWith {
            case NonFatal(e) =>
              val msg = s"Cannot perform reduce step, got ${e.toString}"
              logger.error(msg, e)
              val q = reduceQuery.getOrElse(initialQuery)
              Future(List(errorResult(q, e, Set(), Nil)))
          }
      }
      .map {
        case Nil          => noResult(initialQuery, Set(), List())
        case List(result) => result.copy(query = initialQuery)
        case results =>
          QueryResult(
            jobId = results.map(_.jobId).mkString("+").take(128),
            node = config.jobs.node,
            dataProvenance = results.toSet[QueryResult].flatMap(_.dataProvenance),
            feedback = results.flatMap(_.feedback),
            timestamp = OffsetDateTime.now(),
            `type` = Shapes.compound,
            algorithm = None,
            data = Some(results.toJson),
            error = None,
            query = initialQuery
          )
      }

  }

  private[dispatch] def noResult(initialQuery: Q,
                                 dataProvenance: DataProvenance,
                                 feedback: UserFeedbacks): QueryResult =
    ErrorJobResult(None, config.jobs.node, OffsetDateTime.now(), None, "No results")
      .asQueryResult(initialQuery, dataProvenance, feedback)

  private[dispatch] def reportResult(initiator: ActorRef)(
      queryResult: QueryResult,
      dataProvenance: DataProvenance,
      feedback: UserFeedbacks
  ): QueryResult = {
    val resultWithProv = queryResult.copy(
      dataProvenance = queryResult.dataProvenance ++ dataProvenance,
      feedback = queryResult.feedback ++ feedback
    )
    initiator ! resultWithProv
    resultWithProv
  }

  private[dispatch] def errorResult(initialQuery: Q,
                                    e: Throwable,
                                    dataProvenance: DataProvenance,
                                    feedback: UserFeedbacks): QueryResult = {
    logger.error(s"Cannot complete query because of ${e.getMessage}", e)
    ErrorJobResult(None, config.jobs.node, OffsetDateTime.now(), None, e.toString)
      .asQueryResult(initialQuery, dataProvenance, feedback)
  }

  private[dispatch] def errorMsgResult(initialQuery: Q,
                                       errorMessage: String,
                                       dataProvenance: DataProvenance,
                                       feedback: UserFeedbacks): QueryResult = {
    logger.error(s"Cannot complete query $initialQuery, cause $errorMessage")
    ErrorJobResult(None, config.jobs.node, OffsetDateTime.now(), None, errorMessage)
      .asQueryResult(initialQuery, dataProvenance, feedback)
  }

  private[dispatch] def algorithmsOfQuery(query: Query): List[AlgorithmSpec] = query match {
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
