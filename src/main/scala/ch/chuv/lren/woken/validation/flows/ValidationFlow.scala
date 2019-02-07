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

package ch.chuv.lren.woken.validation.flows

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.{ NonEmptyList, Validated }
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.fp.{ runLater, runNow }
import ch.chuv.lren.woken.core.streams.debugElements
import ch.chuv.lren.woken.core.model.jobs.ValidationJob
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.AlgorithmSpec
import ch.chuv.lren.woken.messages.validation._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import com.typesafe.scalalogging.LazyLogging
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.language.{ higherKinds, postfixOps }

object ValidationFlow {

  private[ValidationFlow] case class Context[R, F[_]](
      job: ValidationJob[F],
      response: R,
      targetMetaData: VariableMetaData,
      groundTruth: List[JsValue]
  )

  private[ValidationFlow] case class Result[F[_]](
      job: ValidationJob[F],
      scores: ScoringResult,
      validationResults: List[JsValue],
      groundTruth: List[JsValue],
      targetMetaData: VariableMetaData
  )

  private[ValidationFlow] case class ValidationScore[F[_]](
      job: ValidationJob[F],
      score: ScoringResult,
      foldScore: ScoringResult,
      validations: List[JsValue]
  )

}

case class ValidationFlow[F[_]: Effect](wokenWorker: WokenWorker[F])(
    implicit materializer: Materializer,
    ec: ExecutionContext
) extends LazyLogging {

  import ValidationFlow.{ Context, Result }

  def validate(): Flow[ValidationJob[F], (ValidationJob[F], Either[String, Score]), NotUsed] =
    Flow[ValidationJob[F]]
      .named("validate-model")
      .map { job =>
        val validation = job.query.algorithm

        // TODO: filter nulls and related parameters may be removed. Need to ensure that incoming query is valid though
        val variablesCanBeNull   = booleanParameter(validation, "variablesCanBeNull")
        val covariablesCanBeNull = booleanParameter(validation, "covariablesCanBeNull")
        val tableService         = job.featuresTableService
        val inputTable           = tableService.table.table

        val featuresQuery = job.query
          .filterNulls(variablesCanBeNull, covariablesCanBeNull)
          .features(inputTable, None)

        logger.whenDebugEnabled(
          logger.debug(s"Validation query: $featuresQuery")
        )

        // JSON objects with fieldname corresponding to variables names
        val (_, dataframe) = runNow(tableService.features(featuresQuery))

        logger.whenDebugEnabled(
          logger.debug(s"Query response: ${dataframe.mkString(",")}")
        )

        // Separate features from labels
        val variables = featuresQuery.dbVariables
        val features  = featuresQuery.dbCovariables ++ featuresQuery.dbGrouping

        val (testData, labels) = dataframe.toList
          .map(
            o =>
              (JsObject(o.fields.filterKeys(features.contains(_))),
               JsObject(o.fields.filterKeys(variables.contains(_))))
          )
          .unzip
        val groundTruth: List[JsValue] = labels.map(_.fields.toList.head._2)

        logger.debug(
          s"Send validation work for all local data to validation worker"
        )

        val model = modelOf(validation).getOrElse(
          throw new IllegalStateException(
            "Should have a model in the validation algorithm parameters"
          )
        )
        val validationQuery = ValidationQuery(-1, model, testData, targetMetadata(job))

        Context(job, validationQuery, validationQuery.varInfo, groundTruth)
      }
      .mapAsync(1)(r => runLater(executeValidation(r)))
      .mapAsync(1)(r => runLater(scoreValidationResponse(r)))
      .map { validationResult =>
        (validationResult.job, validationResult.scores.result)
      }
      .log("Validation result")
      .withAttributes(debugElements)

  private def modelOf(spec: AlgorithmSpec): Option[JsObject] =
    spec.parameters.find(_.code == "model").map(_.value.parseJson.asJsObject)

  private def booleanParameter(spec: AlgorithmSpec, parameter: String): Boolean =
    spec.parameters.find(_.code == parameter).exists(_.value.toBoolean)

  private def targetMetadata(job: ValidationJob[F]): VariableMetaData = {
    import ch.chuv.lren.woken.core.features.Queries._
    job.query.dbVariables.headOption
      .flatMap { v =>
        job.metadata.find(m => m.code == v)
      }
      .getOrElse(throw new Exception("Problem with variables' meta data!"))
  }

  private def executeValidation(
      context: Context[ValidationQuery, F]
  ): F[Context[ValidationResult, F]] = {

    implicit val askTimeout: Timeout = Timeout(5 minutes)
    val validationQuery              = context.response
    val validationResult             = wokenWorker.validate(validationQuery)

    validationResult.map(
      r =>
        Context[ValidationResult, F](job = context.job,
                                     response = r,
                                     targetMetaData = context.targetMetaData,
                                     groundTruth = context.groundTruth)
    )
  }

  private def scoreValidationResponse(
      context: Context[ValidationResult, F]
  ): F[Result[F]] = {
    import cats.syntax.list._

    val resultsV: Validation[NonEmptyList[JsValue]] = Validated
      .fromEither(context.response.result.leftMap(e => NonEmptyList(e, Nil)))
      .andThen { v: List[JsValue] =>
        Validated.fromOption(v.toNel, NonEmptyList(s"No results from evaluation of model", Nil))
      }
    val groundTruthV: Validation[NonEmptyList[JsValue]] = Validated.fromOption(
      context.groundTruth.toNel,
      NonEmptyList(s"Empty test set from local data", Nil)
    )

    def performScoring(algorithmOutput: NonEmptyList[JsValue],
                       groundTruth: NonEmptyList[JsValue]): F[Result[F]] = {

      implicit val askTimeout: Timeout = Timeout(5 minutes)

      val scoringQuery = ScoringQuery(algorithmOutput, groundTruth, context.targetMetaData)

      logger.info(s"scoringQuery: $scoringQuery")
      wokenWorker
        .score(scoringQuery)
        .map(
          s =>
            Result(
              job = context.job,
              scores = s,
              validationResults = algorithmOutput.toList,
              groundTruth = groundTruth.toList,
              targetMetaData = context.targetMetaData
          )
        )
    }

    val foldResultV = (resultsV, groundTruthV) mapN performScoring

    foldResultV.valueOr(e => {
      val errorMsg = e.toList.mkString(",")
      logger.error(s"Cannot perform scoring on $context: $errorMsg")
      Effect[F].raiseError(new Exception(errorMsg))
    })
  }

}
