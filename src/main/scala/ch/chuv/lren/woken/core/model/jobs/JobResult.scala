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

package ch.chuv.lren.woken.core.model.jobs

import java.time.OffsetDateTime
import java.util.Base64

import ch.chuv.lren.woken.messages.query._
import Shapes.{ pfa => pfaShape, _ }
import ch.chuv.lren.woken.core.model.jobs.PfaJobResult.ValidationResults
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.APIJsonProtocol
import spray.json._
import APIJsonProtocol._

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

object PfaJobResult {
  type ValidationResults = Map[ValidationSpec, Either[String, Score]]
}

/**
  * A PFA result (Portable Format for Analytics http://dmg.org/pfa/)
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param algorithm Name of the algorithm
  * @param rawModel PFA model, without validations that are injected dynamically. The full model can be retrived by the method `model`
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
case class PfaJobResult(jobId: String,
                        node: String,
                        timestamp: OffsetDateTime,
                        algorithm: String,
                        rawModel: JsObject,
                        validations: ValidationResults = Map())
    extends JobResult {

  def shape: Shape = pfaShape

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = Some(algorithm)

  def model: JsObject =
    if (validations.isEmpty) rawModel
    else {
      val cells        = rawModel.fields.getOrElse("cells", JsObject()).asJsObject
      val updatedCells = JsObject(cells.fields + ("validations" -> validationsJson))
      JsObject(rawModel.fields + ("cells" -> updatedCells))
    }

  def modelWithoutValidation: JsObject = {
    val cells        = rawModel.fields.getOrElse("cells", JsObject()).asJsObject
    val updatedCells = JsObject(cells.fields - "validations")
    JsObject(rawModel.fields + ("cells" -> updatedCells))
  }

  def injectCell(name: String, value: JsValue): PfaJobResult = {
    val cells        = rawModel.fields.getOrElse("cells", JsObject()).asJsObject
    val updatedCells = JsObject(cells.fields + (name -> value))
    val updatedModel = JsObject(rawModel.fields + ("cells" -> updatedCells))

    copy(rawModel = updatedModel)
  }

  private def validationsJson: JsObject = {
    // TODO: generate proper Avro types
    val values = JsArray(
      validations
        .map({
          case (key, Right(value)) =>
            JsObject("code"           -> JsString(key.code),
                     "validationSpec" -> key.toJson,
                     "node"           -> JsString(nodeOf(key).getOrElse(node)),
                     "data"           -> value.toJson)
          case (key, Left(message)) =>
            JsObject("code"           -> JsString(key.code),
                     "validationSpec" -> key.toJson,
                     "node"           -> JsString(nodeOf(key).getOrElse(node)),
                     "error"          -> JsString(message))
        })
        .toVector
    )
    val `type` = JsObject("type" -> JsString("array"), "items" -> JsString("Record"))
    JsObject(
      "type" -> `type`,
      "init" -> values
    )
  }

  private def nodeOf(spec: ValidationSpec): Option[String] =
    spec.parameters.find(_.code == "node").map(_.value)

}

/**
  * Result of an experiment, i.e. the execution of multiple algorithms over the same datasets
  *
  * @param jobId Id of the job
  * @param node Node where the algorithm is executed
  * @param timestamp Date of execution
  * @param results List of models produced by the experiment
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
case class ExperimentJobResult(jobId: String,
                               node: String,
                               results: Map[AlgorithmSpec, JobResult],
                               timestamp: OffsetDateTime = OffsetDateTime.now())
    extends JobResult {

  def shape: Shape = pfaExperiment

  override def jobIdM: Option[String] = Some(jobId)

  override def algorithmM: Option[String] = None

  def models: JsArray = {
    implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
      queryProtocol.OffsetDateTimeJsonFormat

    import JobResult._
    import queryProtocol._

    // Concatenate results
    JsArray(
      results.map { r =>
        val obj = r._2.asQueryResult(None).toJson.asJsObject
        JsObject(obj.fields + ("algorithmSpec" -> r._1.toJson))
      }.toVector
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

  def asQueryResult(jobResult: JobResult, query: Option[Query]): QueryResult =
    jobResult match {
      case pfa: PfaJobResult =>
        QueryResult(
          jobId = Some(pfa.jobId),
          node = pfa.node,
          timestamp = pfa.timestamp,
          `type` = pfaShape,
          algorithm = Some(pfa.algorithm),
          data = Some(pfa.model),
          error = None,
          query = query
        )
      case pfa: ExperimentJobResult =>
        QueryResult(
          jobId = Some(pfa.jobId),
          node = pfa.node,
          timestamp = pfa.timestamp,
          `type` = pfaExperiment,
          algorithm = None,
          data = Some(pfa.models),
          error = None,
          query = query
        )
      case v: JsonDataJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = v.data,
          error = None,
          query = query
        )
      case v: OtherDataJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = v.data.map(JsString.apply),
          error = None,
          query = query
        )
      case v: SerializedModelJobResult =>
        QueryResult(
          jobId = Some(v.jobId),
          node = v.node,
          timestamp = v.timestamp,
          `type` = v.shape,
          algorithm = Some(v.algorithm),
          data = Some(JsString(Base64.getEncoder.encodeToString(v.data))),
          error = None,
          query = query
        )
      case e: ErrorJobResult =>
        QueryResult(
          jobId = e.jobId,
          node = e.node,
          timestamp = e.timestamp,
          `type` = error,
          algorithm = e.algorithm,
          data = None,
          error = Some(e.error),
          query = query
        )
    }

  implicit class ToQueryResult(val jobResult: JobResult) extends AnyVal {
    def asQueryResult(query: Option[Query]): QueryResult = JobResult.asQueryResult(jobResult, query)
  }

  def fromQueryResult(queryResult: QueryResult): JobResult =
    queryResult.`type` match {

      case Shapes.pfa =>
        val rawModel = queryResult.data.map(_.asJsObject).getOrElse(JsObject())
        val validations: ValidationResults = rawModel.fields
          .get("cells")
          .flatMap(_.asJsObject.fields.get("validations"))
          .map(toValidations)
          .getOrElse(Map())

        PfaJobResult(
          jobId = queryResult.jobId.getOrElse(""),
          node = queryResult.node,
          timestamp = queryResult.timestamp,
          algorithm = queryResult.algorithm.getOrElse(""),
          rawModel = rawModel,
          validations = validations
        )

      case Shapes.pfaExperiment =>
        val results: Map[AlgorithmSpec, JobResult] = queryResult.data
          .map(toExperimentResults)
          .getOrElse(Map())

        ExperimentJobResult(
          jobId = queryResult.jobId.getOrElse(""),
          node = queryResult.node,
          timestamp = queryResult.timestamp,
          results = results
        )
      case Shapes.error =>
        ErrorJobResult(jobId = queryResult.jobId,
                       node = queryResult.node,
                       timestamp = queryResult.timestamp,
                       algorithm = queryResult.algorithm,
                       error = queryResult.error.getOrElse(""))

      case shape if Shapes.visualisationJsonResults.contains(shape) =>
        JsonDataJobResult(
          jobId = queryResult.jobId.getOrElse(""),
          node = queryResult.node,
          timestamp = queryResult.timestamp,
          algorithm = queryResult.algorithm.getOrElse(""),
          shape = shape,
          data = queryResult.data
        )

      case shape if Shapes.visualisationOtherResults.contains(shape) =>
        OtherDataJobResult(
          jobId = queryResult.jobId.getOrElse(""),
          node = queryResult.node,
          timestamp = queryResult.timestamp,
          algorithm = queryResult.algorithm.getOrElse(""),
          shape = shape,
          data = queryResult.data.map {
            case JsString(s) => s
            case _ =>
              deserializationError("Expected a string")
              ""
          }
        )

      case shape =>
        deserializationError(s"Unexpected shape $shape")
    }

  def toExperimentResults(data: JsValue): Map[AlgorithmSpec, JobResult] = data match {
    case l: JsArray =>
      l.elements.map { v =>
        val jobResult = fromQueryResult(v.convertTo[QueryResult])
        val spec      = v.asJsObject.fields("algorithmSpec").convertTo[AlgorithmSpec]
        spec -> jobResult
      }.toMap
    case _ => deserializationError("Expected an array")
  }

  def toValidations(validations: JsValue): ValidationResults =
    validations.asJsObject.getFields("init") match {
      case Seq(JsArray(elements)) =>
        elements.map { v =>
          val validationSpecJson =
            v.asJsObject.fields.getOrElse("validationSpec",
                                          deserializationError(s"Expected a validationSpec"))

          val validationSpec = validationSpecJson.convertTo[ValidationSpec]
          val score: Either[String, Score] = v.asJsObject.fields
            .get("data")
            .fold {
              val error = v.asJsObject.fields.getOrElse("error", JsString("")).convertTo[String]
              Left(error).asInstanceOf[Either[String, Score]]
            } { data =>
              Right(data.convertTo[Score])
            }
          validationSpec -> score
        }.toMap
      case _ =>
        deserializationError(
          s"Expected an init field containing the array of validations, found ${validations.compactPrint}"
        )
    }
}
