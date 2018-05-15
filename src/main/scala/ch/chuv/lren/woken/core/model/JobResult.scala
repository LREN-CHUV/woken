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

package ch.chuv.lren.woken.core.model

import java.time.OffsetDateTime
import java.util.Base64

import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, QueryResult, Shapes, queryProtocol }
import Shapes.{ pfa => pfaShape, _ }
import spray.json._

/**
  * Result produced during the execution of an algorithm
  */
sealed trait JobResult extends Product with Serializable {

  /** Id of the job */
  def jobIdM: Option[String]

  /** Node where the algorithm is executed */
  def node: String

  /** Date of execution */
  def timestamp: OffsetDateTime

  /** Name of the algorithm */
  def algorithmM: Option[String]

  /** Shape of the results (mime type) */
  def shape: Shape

}

/**
  * A PFA result (Portable Format for Analytics http://dmg.org/pfa/)
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param algorithm Name of the algorithm
  * @param model PFA model
  */
case class PfaJobResult(jobId: String,
                        node: String,
                        timestamp: OffsetDateTime,
                        algorithm: String,
                        model: JsObject)
    extends JobResult {

  def shape: Shape = pfaShape

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = Some(algorithm)

  def injectCell(name: String, value: JsValue): PfaJobResult = {
    val cells        = model.fields.getOrElse("cells", JsObject()).asJsObject
    val updatedCells = JsObject(cells.fields + (name -> value))
    val updatedModel = JsObject(model.fields + ("cells" -> updatedCells))

    copy(model = updatedModel)
  }

}

/**
  * Result of an experiment, i.e. the execution of multiple algorithms over the same datasets
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param models List of models produced by the experiment
  */
case class PfaExperimentJobResult(jobId: String,
                                  node: String,
                                  timestamp: OffsetDateTime,
                                  models: JsArray)
    extends JobResult {

  def shape: Shape = pfaExperiment

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = None

}

object PfaExperimentJobResult {

  def apply(results: Map[AlgorithmSpec, JobResult],
            experimentJobId: String,
            experimentNode: String): PfaExperimentJobResult = {

    implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
      queryProtocol.OffsetDateTimeJsonFormat

    import JobResult._
    import queryProtocol._

    // Concatenate results
    val output = JsArray(
      results
        .map(r => r._2.asQueryResult.toJson)
        .toVector
    )

    PfaExperimentJobResult(
      jobId = experimentJobId,
      node = experimentNode,
      timestamp = OffsetDateTime.now(),
      models = output
    )
  }
}

/**
  * Result of a failed job
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param algorithm Name of the algorithm
  * @param error Error to report
  */
case class ErrorJobResult(jobId: Option[String],
                          node: String,
                          timestamp: OffsetDateTime,
                          algorithm: Option[String],
                          error: String)
    extends JobResult {

  def shape: Shape = Shapes.error

  override def jobIdM: Option[String] = jobId

  override def algorithmM: Option[String] = algorithm

}

/**
  * Result producing a visualisation or a table or any other output to display to the user
  */
sealed trait VisualisationJobResult extends JobResult

/**
  * A visualisation result encoded in Json
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param shape Shape of the data (mime type)
  * @param algorithm Name of the algorithm
  * @param data Json encoded data result
  */
case class JsonDataJobResult(jobId: String,
                             node: String,
                             timestamp: OffsetDateTime,
                             shape: Shape,
                             algorithm: String,
                             data: Option[JsValue])
    extends VisualisationJobResult {

  assert(Shapes.visualisationJsonResults.contains(shape))

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = Some(algorithm)

}

/**
  * A visualisation or other type of user-facing information encoded as a string
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param shape Shape of the data (mime type)
  * @param algorithm Name of the algorithm
  * @param data Data result as a string
  */
case class OtherDataJobResult(jobId: String,
                              node: String,
                              timestamp: OffsetDateTime,
                              shape: Shape,
                              algorithm: String,
                              data: Option[String])
    extends VisualisationJobResult {

  assert(Shapes.visualisationOtherResults.contains(shape))

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = Some(algorithm)

}

/**
  * A model serialized in binary
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param shape Shape of the data (mime type)
  * @param algorithm Name of the algorithm
  * @param data Binary for the serialized model
  */
case class SerializedModelJobResult(jobId: String,
                                    node: String,
                                    timestamp: OffsetDateTime,
                                    shape: Shape,
                                    algorithm: String,
                                    data: Array[Byte])
    extends VisualisationJobResult {

  assert(Shapes.serializedModelsResults.contains(shape))

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = Some(algorithm)

}

object JobResult {

  def asQueryResult(jobResult: JobResult): QueryResult =
    jobResult match {
      case pfa: PfaJobResult =>
        QueryResult(
          jobId = Some(pfa.jobId),
          node = pfa.node,
          timestamp = pfa.timestamp,
          `type` = pfaShape,
          algorithm = Some(pfa.algorithm),
          data = Some(pfa.model),
          error = None
        )
      case pfa: PfaExperimentJobResult =>
        QueryResult(
          jobId = Some(pfa.jobId),
          node = pfa.node,
          timestamp = pfa.timestamp,
          `type` = pfaExperiment,
          algorithm = None,
          data = Some(pfa.models),
          error = None
        )
      case v: JsonDataJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = v.data,
          error = None
        )
      case v: OtherDataJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = v.data.map(JsString.apply),
          error = None
        )
      case v: SerializedModelJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = Some(JsString(Base64.getEncoder.encodeToString(v.data))),
          error = None
        )
      case e: ErrorJobResult =>
        QueryResult(
          jobId = e.jobId,
          node = e.node,
          timestamp = e.timestamp,
          `type` = error,
          algorithm = e.algorithm,
          data = None,
          error = Some(e.error)
        )
    }

  implicit class ToQueryResult(val jobResult: JobResult) extends AnyVal {
    def asQueryResult: QueryResult = JobResult.asQueryResult(jobResult)
  }

}
