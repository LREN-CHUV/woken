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
import cats._
import cats.effect.Effect
import com.typesafe.scalalogging.LazyLogging
import ch.chuv.lren.woken.messages.query.Shapes.{ error => errorShape, _ }
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.core.json.yaml
import ch.chuv.lren.woken.core.json.yaml.Yaml
import spray.json._
import sup.HealthCheck

import scala.language.higherKinds
import scala.util.Try

case class WokenRepositoryDAO[F[_]: Effect](xa: Transactor[F]) extends WokenRepository[F] {

  private val genTableNum = sql"""
      SELECT nextval('gen_features_table_seq');
    """.query[Int].unique

  override def nextTableSeqNumber(): F[Int] = genTableNum.transact(xa)

  override val jobResults: JobResultRepositoryDAO[F] = new JobResultRepositoryDAO[F](xa)

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

/**
  * Interpreter based on Doobie that provides the operations of the algebra
  */
class JobResultRepositoryDAO[F[_]: Effect](val xa: Transactor[F])
    extends JobResultRepository[F]
    with LazyLogging {

  protected implicit val ShapeMeta: Meta[Shape] =
    Meta[String].timap(
      s => fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid shape: $s"))
    )(
      shape => shape.mime
    )

  type JobResultColumns =
    (String, String, OffsetDateTime, Shape, Option[String], Option[String], Option[String])

  private val unsafeFromColumns: JobResultColumns => JobResult = {
    case (jobId, node, timestamp, shape, function, _, Some(errorMessage)) if shape == errorShape =>
      ErrorJobResult(Some(jobId), node, Set(), timestamp, function, errorMessage)
    case (jobId, node, timestamp, _, function, data, Some(errorMessage))
        if data.isEmpty && errorMessage.trim.nonEmpty =>
      ErrorJobResult(Some(jobId), node, Set(), timestamp, function, errorMessage)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some("")) if shape == pfa =>
      Try(
        PfaJobResult(jobId,
                     node,
                     Set(),
                     timestamp,
                     function.getOrElse(""),
                     data.parseJson.asJsObject)
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, Set(), timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, _, Some(data), None) if pfaExperiment == shape =>
      Try(
        ExperimentJobResult(jobId,
                            node,
                            Set(),
                            JobResult.toExperimentResults(data.parseJson),
                            timestamp)
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId for a PFA experiment is not a valid Json array"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, Set(), timestamp, None, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if pfaYaml == shape =>
      PfaJobResult(jobId,
                   node,
                   Set(),
                   timestamp,
                   function.getOrElse(""),
                   yaml.yaml2Json(Yaml(data)).asJsObject)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if visualisationJsonResults.contains(shape) =>
      Try {
        val json = data.parseJson
        JsonDataJobResult(jobId, node, Set(), timestamp, shape, function.getOrElse(""), Some(json))
      }.recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(Some(jobId), node, Set(), timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, None, None | Some(""))
        if visualisationJsonResults.contains(shape) =>
      JsonDataJobResult(jobId, node, Set(), timestamp, shape, function.getOrElse(""), None)
    case (jobId, node, timestamp, shape, function, data, None | Some(""))
        if visualisationOtherResults.contains(shape) =>
      OtherDataJobResult(jobId, node, Set(), timestamp, shape, function.getOrElse(""), data)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if serializedModelsResults.contains(shape) =>
      SerializedModelJobResult(jobId,
                               node,
                               Set(),
                               timestamp,
                               shape,
                               function.getOrElse(""),
                               Base64.getDecoder.decode(data))
    case (jobId, node, timestamp, shape, function, data, error) =>
      val msg =
        s"Cannot handle job results of shape $shape produced by function $function with data $data, error $error"
      logger.warn(msg)
      ErrorJobResult(Some(jobId), node, Set(), timestamp, function, msg)
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
