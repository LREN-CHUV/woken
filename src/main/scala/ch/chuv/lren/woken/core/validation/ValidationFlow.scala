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

package ch.chuv.lren.woken.core.validation

import akka.NotUsed
import akka.actor.{ ActorContext, ActorRef }
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.event.Logging
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.{ NonEmptyList, Validated }
import cats.implicits._
import ch.chuv.lren.woken.core.CoordinatorActor
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.ValidationJob
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.query.AlgorithmSpec
import ch.chuv.lren.woken.messages.validation._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.postfixOps

object ValidationFlow {

  private[ValidationFlow] case class Context[R](
      job: ValidationJob,
      response: R,
      targetMetaData: VariableMetaData,
      groundTruth: List[JsValue]
  )

  private[ValidationFlow] case class Result(
      job: ValidationJob,
      scores: ScoringResult,
      validationResults: List[JsValue],
      groundTruth: List[JsValue],
      targetMetaData: VariableMetaData
  )

  private[ValidationFlow] case class ValidationScore(
      job: ValidationJob,
      score: ScoringResult,
      foldScore: ScoringResult,
      validations: List[JsValue]
  )

}

case class ValidationFlow(
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private val log = Logging(context.system, getClass)

  private lazy val mediator: ActorRef = DistributedPubSub(context.system).mediator

  import ValidationFlow.{ Context, Result }

  def validate(): Flow[ValidationJob, (ValidationJob, Either[String, Score]), NotUsed] =
    Flow[ValidationJob]
      .map { job =>
        val validation = job.query.algorithm

        val variablesCanBeNull   = booleanParameter(validation, "variablesCanBeNull")
        val covariablesCanBeNull = booleanParameter(validation, "covariablesCanBeNull")

        val featuresQuery =
          job.query
            .filterNulls(variablesCanBeNull, covariablesCanBeNull)
            .features(job.inputTable, None)

        val sql = featuresQuery.sql

        log.info(s"Validation query: $featuresQuery")

        // JSON objects with fieldname corresponding to variables names
        val (_, dataframe) = featuresDatabase.runQuery(featuresDatabase.ldsmConnection, sql)

        log.info(s"Query response: ${dataframe.mkString(",")}")

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

        log.info(
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
      .mapAsync(1)(executeValidation)
      .mapAsync(1)(scoreValidationResponse)
      .map { validationResult =>
        (validationResult.job, validationResult.scores.result)
      }
      .log("Validation result")
      .named("validate-model")

  private def modelOf(spec: AlgorithmSpec): Option[JsObject] =
    spec.parameters.find(_.code == "model").map(_.value.parseJson.asJsObject)

  private def booleanParameter(spec: AlgorithmSpec, parameter: String): Boolean =
    spec.parameters.find(_.code == parameter).exists(_.value.toBoolean)

  private def targetMetadata(job: ValidationJob): VariableMetaData = {
    import ch.chuv.lren.woken.core.features.Queries._
    job.query.dbVariables.headOption
      .flatMap { v =>
        job.metadata.find(m => m.code == v)
      }
      .getOrElse(throw new Exception("Problem with variables' meta data!"))
  }

  private def executeValidation(
      context: Context[ValidationQuery]
  ): Future[Context[ValidationResult]] = {

    implicit val askTimeout: Timeout = Timeout(5 minutes)
    val validationQuery              = context.response
    val validationResult             = remoteValidate(validationQuery)

    validationResult.map(
      r =>
        Context[ValidationResult](job = context.job,
                                  response = r,
                                  targetMetaData = context.targetMetaData,
                                  groundTruth = context.groundTruth)
    )
  }

  private def scoreValidationResponse(
      context: Context[ValidationResult]
  ): Future[Result] = {
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
                       groundTruth: NonEmptyList[JsValue]): Future[Result] = {

      implicit val askTimeout: Timeout = Timeout(5 minutes)

      val scoringQuery = ScoringQuery(algorithmOutput, groundTruth, context.targetMetaData)

      log.info(s"scoringQuery: $scoringQuery")
      remoteScore(scoringQuery)
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
      log.error(s"Cannot perform scoring on $context: $errorMsg")
      Future.failed(new Exception(errorMsg))
    })
  }

  private def remoteValidate(validationQuery: ValidationQuery): Future[ValidationResult] = {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    log.debug(s"validationQuery: $validationQuery")
    val future = mediator ? DistributedPubSubMediator.Send("/user/validation",
                                                           validationQuery,
                                                           localAffinity = false)
    future.mapTo[ValidationResult]
  }

  private def remoteScore(scoringQuery: ScoringQuery): Future[ScoringResult] = {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    log.debug(s"scoringQuery: $scoringQuery")
    val future = mediator ? DistributedPubSubMediator.Send("/user/scoring",
                                                           scoringQuery,
                                                           localAffinity = false)
    future.mapTo[ScoringResult]
  }
}
