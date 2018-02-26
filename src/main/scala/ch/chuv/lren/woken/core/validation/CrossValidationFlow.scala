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

import java.util.UUID

import akka.NotUsed
import akka.actor.{ ActorContext, ActorSelection }
import akka.event.Logging
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.{ NonEmptyList, Validated }
import cats.implicits._
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.features.QueryOffset
import ch.chuv.lren.woken.core.{ CoordinatorActor, CoordinatorConfig }
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, PfaJobResult }
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{ MiningQuery, ValidationSpec }
import ch.chuv.lren.woken.messages.validation._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import spray.json.JsValue

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

object CrossValidationFlow {

  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: MiningQuery,
      metadata: List[VariableMetaData],
      validation: ValidationSpec,
      algorithmDefinition: AlgorithmDefinition
  )

  private[CrossValidationFlow] case class FoldContext[R](
      job: Job,
      response: R,
      fold: Int,
      targetMetaData: VariableMetaData,
      validation: KFoldCrossValidation
  )

  private[CrossValidationFlow] case class FoldResult(
      job: Job,
      scores: ScoringResult,
      validationResults: List[JsValue],
      groundTruth: List[JsValue],
      fold: Int,
      targetMetaData: VariableMetaData
  )

  private[CrossValidationFlow] case class CrossValidationScore(
      job: Job,
      score: ScoringResult,
      foldScores: Map[Int, ScoringResult],
      validations: List[JsValue]
  )

}

case class CrossValidationFlow(
    coordinatorConfig: CoordinatorConfig,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private val log = Logging(context.system, getClass)

  private val validationActor: ActorSelection =
    context.actorSelection("/user/entrypoint/mainRouter/validationWorker")
  private val scoringActor: ActorSelection =
    context.actorSelection("/user/entrypoint/mainRouter/scoringWorker")

  import CrossValidationFlow._

  def crossValidate(
      parallelism: Int
  ): Flow[CrossValidationFlow.Job, (CrossValidationFlow.Job, KFoldCrossValidationScore), NotUsed] =
    Flow[CrossValidationFlow.Job]
      .map { job =>
        val validation = job.validation
        val foldCount  = validation.parametersAsMap("k").toInt
        val featuresQuery =
          job.query.features(job.inputTable, !job.algorithmDefinition.supportsNullValues, None)

        log.info(s"List of folds: $foldCount")

        // TODO For now only kfold cross-validation
        val crossValidation =
          KFoldCrossValidation(featuresQuery, foldCount, coordinatorConfig.featuresDatabase)

        assert(crossValidation.partition.size == foldCount,
          s"Excepted number of folds ($foldCount) to match the number of partitions (${crossValidation.partition.size})")

        // For every fold
        crossValidation.partition.toList.map((job, crossValidation, _))
      }
      .mapConcat(identity)
      .mapAsyncUnordered(parallelism) { f =>
        val (job, crossValidation, (fold, (s, n))) = f
        localJobForFold(job, s, n, fold, crossValidation)
      }
      .mapAsync(parallelism)(validateFoldJobResponse)
      .mapAsync(parallelism)(scoreFoldValidationResponse)
      .fold[List[FoldResult]](List[FoldResult]()) { (l, r) =>
        l :+ r
      }
      .mapAsync(1) { foldResults =>
        scoreAll(foldResults.sortBy(_.fold))
      }
      .map { crossValidationScore =>
        crossValidationScore.score.result match {
          case Right(score: VariableScore) =>
            (crossValidationScore.job,
             KFoldCrossValidationScore(
               average = score,
               folds = crossValidationScore.foldScores
                 .filter {
                   case (k, ScoringResult(Left(error))) =>
                     log.warning(s"Fold $k failed with message $error")
                     false
                   case _ => true
                 }
                 .map {
                   case (k, ScoringResult(Right(score: VariableScore))) => (k, score)
                 }
             ))
          case Left(error) =>
            log.warning(s"Global score failed with message $error")
            throw new RuntimeException(error)
        }
      // Aggregation of results from all folds

      }
      .named("crossValidate")

  private def targetMetadata(job: Job) = {
    import ch.chuv.lren.woken.core.features.Queries._
    job.query.dbVariables.headOption
      .flatMap { v =>
        job.metadata.find(m => m.code == v)
      }
      .getOrElse(throw new Exception("Problem with variables' meta data!"))
  }

  private def localJobForFold(
      job: Job,
      s: Int,
      n: Int,
      fold: Int,
      validation: KFoldCrossValidation
  ): Future[FoldContext[CoordinatorActor.Response]] = {

    // Spawn a LocalCoordinatorActor for that one particular fold
    val jobId = UUID.randomUUID().toString
    val featuresQuery = job.query.features(job.inputTable,
                                           !job.algorithmDefinition.supportsNullValues,
                                           Some(QueryOffset(s, n)))

    val subJob = DockerJob(
      jobId = jobId,
      dockerImage = job.algorithmDefinition.dockerImage,
      inputDb = job.inputDb,
      query = featuresQuery,
      algorithmSpec = job.query.algorithm,
      metadata = job.metadata
    )

    CoordinatorActor
      .future(subJob, coordinatorConfig, context)
      .map(
        response =>
          FoldContext[CoordinatorActor.Response](job = job,
                                                 response = response,
                                                 fold = fold,
                                                 targetMetaData = targetMetadata(job),
                                                 validation = validation)
      )
  }

  private def validateFoldJobResponse(
      context: FoldContext[CoordinatorActor.Response]
  ): Future[FoldContext[ValidationResult]] =
    (context.response match {
      case CoordinatorActor.Response(_, List(pfa: PfaJobResult)) =>
        // Validate the results
        log.info("Received result from local method.")
        val model    = pfa.model
        val fold     = context.fold
        val testData = context.validation.getTestSet(fold)._1

        log.info(
          s"Send a validation work for fold $fold to validation worker: ${validationActor.pathString}"
        )
        implicit val askTimeout: Timeout = Timeout(5 minutes)
        (validationActor ? ValidationQuery(fold, model, testData, context.targetMetaData))
          .mapTo[ValidationResult]

      case CoordinatorActor.Response(_, List(error: ErrorJobResult)) =>
        val message =
          s"Error on cross validation job ${error.jobId} during fold ${context.fold}" +
            s" on variable ${context.targetMetaData.code}: ${error.error}"
        log.error(message)
        // On training fold fails, we notify supervisor and we stop
        Future.failed[ValidationResult](new IllegalStateException(message))

      case CoordinatorActor.Response(_, unhandled) =>
        val message =
          s"Error on cross validation job ${context.job.jobId} during fold ${context.fold}" +
            s" on variable ${context.targetMetaData.code}: Unhandled response from CoordinatorActor: $unhandled"
        log.error(message)
        // On training fold fails, we notify supervisor and we stop
        Future.failed[ValidationResult](new IllegalStateException(message))
    }).map(
      r =>
        FoldContext[ValidationResult](job = context.job,
                                      response = r,
                                      fold = context.fold,
                                      targetMetaData = context.targetMetaData,
                                      validation = context.validation)
    )

  private def scoreFoldValidationResponse(
      context: FoldContext[ValidationResult]
  ): Future[FoldResult] = {
    import cats.syntax.list._

    val resultsV: Validation[NonEmptyList[JsValue]] = Validated
      .fromEither(context.response.result.leftMap(e => NonEmptyList(e, Nil)))
      .andThen { v: List[JsValue] =>
        Validated.fromOption(v.toNel, NonEmptyList(s"No results on fold ${context.fold}", Nil))
      }
    val groundTruthV: Validation[NonEmptyList[JsValue]] = Validated.fromOption(
      context.validation.groundTruth(context.fold).toNel,
      NonEmptyList(s"Empty test set on fold ${context.fold}", Nil)
    )

    def performScoring(algorithmOutput: NonEmptyList[JsValue],
                       groundTruth: NonEmptyList[JsValue]): Future[FoldResult] = {
      implicit val askTimeout: Timeout = Timeout(5 minutes)
      (scoringActor ? ScoringQuery(algorithmOutput, groundTruth, context.targetMetaData))
        .mapTo[ScoringResult]
        .map(
          s =>
            FoldResult(
              job = context.job,
              scores = s,
              validationResults = algorithmOutput.toList,
              groundTruth = groundTruth.toList,
              fold = context.fold,
              targetMetaData = context.targetMetaData
          )
        )
    }

    val foldResultV = (resultsV, groundTruthV) mapN performScoring

    foldResultV.valueOr(e => Future.failed(new Exception(e.toList.mkString(","))))
  }

  private def scoreAll(foldResults: Seq[FoldResult]): Future[CrossValidationScore] = {

    import cats.syntax.list._

    val validations  = foldResults.flatMap(_.validationResults).toList
    val groundTruths = foldResults.flatMap(_.groundTruth).toList
    val foldScores = foldResults.map { s =>
      s.fold -> s.scores
    }.toMap

    (validations.toNel, groundTruths.toNel) match {
      case (Some(r), Some(gt)) =>
        implicit val askTimeout: Timeout = Timeout(5 minutes)
        (scoringActor ? ScoringQuery(r, gt, foldResults.head.targetMetaData))
          .mapTo[ScoringResult]
          .map { score =>
            CrossValidationScore(job = foldResults.head.job,
                                 score = score,
                                 foldScores = foldScores,
                                 validations = validations)
          }
      case (r, gt) =>
        val message =
          s"Final reduce for cross-validation uses empty datasets: Validations = $r, ground truths = $gt"
        log.error(message)
        Future(
          CrossValidationScore(job = foldResults.head.job,
                               score = ScoringResult(Left(message)),
                               foldScores = foldScores,
                               validations = validations)
        )

    }

  }

}
