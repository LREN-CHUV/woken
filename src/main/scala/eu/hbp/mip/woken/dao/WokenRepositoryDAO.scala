/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.dao

import java.time.OffsetDateTime

import doobie._
import doobie.implicits._
import cats._
import com.typesafe.scalalogging.LazyLogging
import eu.hbp.mip.woken.core.model.Shapes.{ error => errorShape, _ }
import eu.hbp.mip.woken.core.model._
import eu.hbp.mip.woken.json.yaml
import eu.hbp.mip.woken.json.yaml.Yaml
import spray.json._

import scala.language.higherKinds
import scala.util.Try

class WokenRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends WokenRepository[F] {

  override val jobResults: JobResultRepositoryDAO[F] = new JobResultRepositoryDAO[F](xa)

}

/**
  * Interpreter based on Doobie that provides the operations of the algebra
  */
class JobResultRepositoryDAO[F[_]: Monad](val xa: Transactor[F])
    extends JobResultRepository[F]
    with LazyLogging {

  protected implicit val ShapeMeta: Meta[Shape] =
    Meta[String].xmap(
      s => fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid shape: $s")),
      shape => shape.mime
    )

  type JobResultColumns =
    (String, String, OffsetDateTime, Shape, String, Option[String], Option[String])

  private val unsafeFromColumns: JobResultColumns => JobResult = {
    case (jobId, node, timestamp, shape, function, _, Some(errorMessage)) if shape == errorShape =>
      ErrorJobResult(jobId, node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, _, function, data, Some(errorMessage))
        if data.isEmpty && errorMessage.trim.nonEmpty =>
      ErrorJobResult(jobId, node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some("")) if shape == pfa =>
      Try(
        PfaJobResult(jobId, node, timestamp, function, data.parseJson.asJsObject)
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(jobId, node, timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, _, Some(data), None) if pfaExperiment == shape =>
      Try(
        PfaExperimentJobResult(jobId, node, timestamp, data.parseJson.asInstanceOf[JsArray])
      ).recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId for a PFA experiment is not a valid Json array"
          logger.warn(msg, t)
          ErrorJobResult(jobId, node, timestamp, "experiment", s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if pfaYaml == shape =>
      PfaJobResult(jobId, node, timestamp, function, yaml.yaml2Json(Yaml(data)).asJsObject)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if visualisationJsonResults.contains(shape) =>
      Try {
        val json = data.parseJson
        JsonDataJobResult(jobId, node, timestamp, shape, function, json)
      }.recover {
        case t: Throwable =>
          val msg = s"Data for job $jobId produced by $function is not a valid Json object"
          logger.warn(msg, t)
          ErrorJobResult(jobId, node, timestamp, function, s"$msg : $t")
      }.get
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if visualisationOtherResults.contains(shape) =>
      OtherDataJobResult(jobId, node, timestamp, shape, function, data)
    case (jobId, node, timestamp, shape, function, data, error) =>
      val msg =
        s"Cannot handle job results of shape $shape produced by function $function with data $data, error $error"
      logger.warn(msg)
      ErrorJobResult(jobId, node, timestamp, function, msg)
  }

  private val jobResultToColumns: JobResult => JobResultColumns = {
    case j: PfaJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       pfa,
       j.algorithm.take(255),
       Some(j.model.compactPrint),
       None)
    case j: PfaExperimentJobResult =>
      val pfa = j.models.compactPrint
      (j.jobId, j.node.take(32), j.timestamp, pfaExperiment, j.algorithm.take(255), Some(pfa), None)
    case j: ErrorJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       errorShape,
       j.algorithm.take(255),
       None,
       Some(j.error.take(255)))
    case j: JsonDataJobResult =>
      (j.jobId,
       j.node.take(32),
       j.timestamp,
       j.shape,
       j.algorithm.take(255),
       Some(j.data.compactPrint),
       None)
    case j: OtherDataJobResult =>
      (j.jobId, j.node.take(32), j.timestamp, j.shape, j.algorithm.take(255), Some(j.data), None)
  }

  private implicit val JobResultComposite: Composite[JobResult] =
    Composite[JobResultColumns].imap(unsafeFromColumns)(jobResultToColumns)

  override def get(jobId: String): F[Option[JobResult]] =
    sql"select job_id, node, timestamp, shape, function, data, error from job_result where job_id = $jobId"
      .query[JobResult]
      .option
      .transact(xa)

  override def put(jobResult: JobResult): F[JobResult] = {
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
      .withUniqueGeneratedKeys[JobResult]("job_id",
                                          "node",
                                          "timestamp",
                                          "shape",
                                          "function",
                                          "data",
                                          "error")
      .transact(xa)
  }

}
