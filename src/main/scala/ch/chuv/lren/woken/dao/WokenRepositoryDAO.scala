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

package ch.chuv.lren.woken.dao

import java.time.OffsetDateTime
import java.util.{ Base64, UUID }

import doobie._
import doobie.implicits._
import cats.Id
import cats.MonadError
import cats.data.Validated._
import cats.effect.Effect
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import ch.chuv.lren.woken.messages.query.{
  ExperimentQuery,
  MiningQuery,
  QueryResult,
  UserId,
  Query => WokenQuery
}
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.messages.query.Shapes.{ error => errorShape, _ }
import ch.chuv.lren.woken.core.model.database.sqlUtils._
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.core.json._
import ch.chuv.lren.woken.core.json.yaml.Yaml
import ch.chuv.lren.woken.core.logging
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.TableId
import spray.json._
import sup.HealthCheck

import scala.language.higherKinds
import scala.util.Try

case class WokenRepositoryDAO[F[_]](xa: Transactor[F])(implicit F: MonadError[F, Throwable]) extends WokenRepository[F] {

  private val genTableNum = sql"""
      SELECT nextval('gen_features_table_seq');
    """.query[Int].unique

  override def nextTableSeqNumber(): F[Int] = genTableNum.transact(xa)

  override val jobResults: JobResultRepositoryDAO[F] = new JobResultRepositoryDAO[F](xa)

  override def resultsCache: ResultsCacheRepository[F] = new ResultsCacheRepositoryDAO[F](xa)

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

/**
  * Interpreter based on Doobie that provides the operations of the algebra
  */
class JobResultRepositoryDAO[F[_]](val xa: Transactor[F])(implicit F: MonadError[F, Throwable])
    extends JobResultRepository[F]
    with LazyLogging {

  implicit val han: LogHandler = logging.doobieLogHandler

  type JobResultColumns =
    (String, String, OffsetDateTime, Shape, Option[String], Option[String], Option[String])

  private val unsafeFromColumns: JobResultColumns => JobResult = {
    case (jobId, node, timestamp, shape, function, _, Some(errorMessage)) if shape == errorShape =>
      ErrorJobResult(Some(jobId), node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, _, function, data, Some(errorMessage))
        if data.isEmpty && errorMessage.trim.nonEmpty =>
      ErrorJobResult(Some(jobId), node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some("")) if shape == pfa =>
      Try(
        PfaJobResult(jobId, node, timestamp, function.getOrElse(""), data.parseJson.asJsObject)
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, _, Some(data), None) if pfaExperiment == shape =>
      Try(
        ExperimentJobResult(jobId, node, JobResult.toExperimentResults(data.parseJson), timestamp)
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId for a PFA experiment is not a valid Json array"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, timestamp, None, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if pfaYaml == shape =>
      PfaJobResult(jobId,
                   node,
                   timestamp,
                   function.getOrElse(""),
                   yaml.yaml2Json(Yaml(data)).asJsObject)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if visualisationJsonResults.contains(shape) =>
      Try {
        val json = data.parseJson
        JsonDataJobResult(jobId, node, timestamp, shape, function.getOrElse(""), Some(json))
      }.recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, None, None | Some(""))
        if visualisationJsonResults.contains(shape) =>
      JsonDataJobResult(jobId, node, timestamp, shape, function.getOrElse(""), None)
    case (jobId, node, timestamp, shape, function, data, None | Some(""))
        if visualisationOtherResults.contains(shape) =>
      OtherDataJobResult(jobId, node, timestamp, shape, function.getOrElse(""), data)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if serializedModelsResults.contains(shape) =>
      SerializedModelJobResult(jobId,
                               node,
                               timestamp,
                               shape,
                               function.getOrElse(""),
                               Base64.getDecoder.decode(data))
    case (jobId, node, timestamp, shape, function, data, error) =>
      val msg =
        s"Cannot handle job results of shape $shape produced by function $function with data $data, error $error"
      logger.warn(msg)
      ErrorJobResult(Some(jobId), node, timestamp, function, msg)
  }

  private val jobResultToColumns: JobResult => JobResultColumns = {
    case j: PfaJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       pfa,
       Some(j.algorithm.take(255)),
       Some(j.model.compactPrint),
       None)
    case j: ExperimentJobResult =>
      val models = j.models.compactPrint
      (j.jobId, j.node.take(32), j.timestamp, pfaExperiment, None, Some(models), None)
    case j: ErrorJobResult =>
      (j.jobId.getOrElse(UUID.randomUUID().toString),
       j.node.take(32),
       j.timestamp,
       errorShape,
       j.algorithm.map(_.take(255)),
       None,
       Some(j.error.take(255)))
    case j: JsonDataJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       j.shape,
       Some(j.algorithm.take(255)),
       j.data.map(_.compactPrint),
       None)
    case j: OtherDataJobResult =>
      (j.jobId, j.node.take(32), j.timestamp, j.shape, Some(j.algorithm.take(255)), j.data, None)
    case j: SerializedModelJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       j.shape,
       Some(j.algorithm.take(255)),
       Some(Base64.getEncoder.encodeToString(j.data)),
       None)
  }

  private implicit val jobResultRead: Read[JobResult] =
    Read[JobResultColumns].map(unsafeFromColumns)

  private implicit val jobResultWrite: Write[JobResult] =
    Write[JobResultColumns].contramap(jobResultToColumns)

  override def get(jobId: String): F[Option[JobResult]] =
    sql"SELECT job_id, node, timestamp, shape, function, data, error FROM job_result WHERE job_id = $jobId"
      .query[JobResult]
      .option
      .transact(xa)

  override def put(jobResult: JobResult): F[JobResult] = {
    val uniqueGeneratedKeys =
      Array("job_id", "node", "timestamp", "shape", "function", "data", "error")
    val update: Update0 = {
      jobResultToColumns(jobResult) match {
        case (jobId, node, timestamp, shape, function, data, error) =>
          sql"""
            INSERT INTO job_result (job_id, node, timestamp, shape, function, data, error)
                   VALUES ($jobId, $node, $timestamp, $shape, $function, $data, $error)
            """.update
        case e => throw new IllegalArgumentException(s"Cannot handle $e")
      }
    }

    update
      .withUniqueGeneratedKeys[JobResult](uniqueGeneratedKeys: _*)
      .transact(xa)
  }

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

class ResultsCacheRepositoryDAO[F[_]](val xa: Transactor[F])(implicit F: MonadError[F, Throwable])
    extends ResultsCacheRepository[F] {

  private val systemUser = UserId("woken")

  implicit val han: LogHandler = logging.doobieLogHandler

  override def put(result: QueryResult, userQuery: WokenQuery): F[Unit] = {

    val query = normalise(userQuery)

    // Extract simple fields
    val node = result.node
    // TODO: Query result should contain a hash of the original contents of the table queried
    val tableContentHash = ""
    val createdAt        = result.timestamp
    val lastUsed         = OffsetDateTime.now()
    val shape            = result.`type`
    val function         = result.algorithm

    // Extract fields that require validation
    val tableNameV: Validation[String] =
      query.targetTable.map(_.toString).toValidNel[String]("Empty table name")
    val queryJsonV: Validation[JsObject] = {
      if (query.variables.length + query.covariables.length + query.grouping.length > 30)
        "High dimension query".invalidNel[JsObject]
      else
        query.toJson.asJsObject.validNel[String]
    }
    val resultJsonV: Validation[JsObject] = queryJsonV.andThen { _ =>
      val json = result.toJson.asJsObject
      if (weightEstimate(json) > 1000000) "Result too big".invalidNel[JsObject]
      else json.validNel[String]
    }

    def insert(tableName: String, queryJson: JsObject, resultJson: JsObject): Update0 =
      sql"""INSERT INTO "results_cache" (
      "node", "table_name", "table_contents_hash", "query", "created_at", "last_used", "data", "shape", "function")
      VALUES ($node, $tableName, $tableContentHash, $queryJson, $createdAt, $lastUsed, $resultJson, $shape, $function)
      ON CONFLICT DO NOTHING""".update

    val insertV = (tableNameV, queryJsonV, resultJsonV) mapN insert

    insertV.fold(
      err => {
        logger.warn(
          s"Cannot store query result in the cache, caused by ${err.toList.mkString(", ")}"
        )
        ().pure[F]
      },
      insert =>
        insert.run.transact(xa).map { count =>
          if (count == 0) {
            logger.warn("Result not stored in the database cache, insert failed")
          }
      }
    )

  }

  override def get(
      node: String,
      table: TableId,
      tableContentsHash: Option[String],
      userQuery: WokenQuery
  ): F[Option[QueryResult]] = {
    val query = normalise(userQuery)

    val queryJsonV: Validation[JsObject] = {
      if (query.variables.length + query.covariables.length + query.grouping.length > 30)
        "High dimension query".invalidNel[JsObject]
      else query.toJson.asJsObject.validNel[String]
    }
    val tableName = table.toString

    queryJsonV.fold(
      err => {
        logger.debug(s"Ignore cached results: ${err.toList.mkString(",")}")
        Option.empty[QueryResult].pure[F]
      },
      queryJson => {
        val filterBase =
          fr"""WHERE "node" = $node AND "table_name" = $tableName AND "query" = $queryJson"""

        val filter = tableContentsHash.fold(filterBase)(
          hash => filterBase ++ fr"""AND "table_content_hash" =""" ++ frConst(hash)
        )

        val q = fr"""SELECT "data" FROM "results_cache"""" ++ filter

        // TODO: handle case of queries returning several results
        q.query[JsObject].map(_.convertTo[QueryResult]).option.transact(xa).flatMap {
          resultOp =>
            resultOp.fold {
              logger.info(
                s"Could not find matching result in the cache for node $node, table $tableName, query ${queryJson.compactPrint}"
              )
              Option.empty[QueryResult].pure[F]
            } { result =>
              val updateTs
                : Fragment = fr"""UPDATE "results_cache" SET "last_used" = now()""" ++ filter
              // Restore user query, as result may come from another user
              updateTs.update.run.transact(xa).map(_ => Some(result.copy(query = userQuery)))
            }
        }
      }
    )
  }

  override def reset(): F[Unit] =
    sql"""DELETE FROM "results_cache";""".update.run
      .transact(xa)
      .map(logDeleted)

  override def cleanUnusedCacheEntries(): F[Unit] =
    sql"""DELETE FROM "results_cache" where "last_used" - "created" > interval '10 days';""".update.run
      .transact(xa)
      .map(logDeleted)

  override def cleanTooManyCacheEntries(maxEntries: Int): F[Unit] =
    sql"""DELETE FROM "results_cache" ORDER BY "last_used" DESC OFFSET 10000;""".update.run
      .transact(xa)
      .map(logDeleted)

  override def cleanCacheEntriesForOldContent(
      table: String,
      tableContentHash: String
  ): F[Unit] =
    sql"""DELETE FROM "results_cache" WHERE "table_name" = $table AND "table_content_hash" != $tableContentHash;""".update.run
      .transact(xa)
      .map(logDeleted)

  private def logDeleted(count: Int): Unit =
    logger.info(s"Deleted $count records from results_cache")

  override def healthCheck: HealthCheck[F, Id] = validate(xa)

  private def normalise(query: WokenQuery): WokenQuery =
    query match {
      case q: MiningQuery     => q.copy(user = systemUser)
      case q: ExperimentQuery => q.copy(user = systemUser)
    }

}
