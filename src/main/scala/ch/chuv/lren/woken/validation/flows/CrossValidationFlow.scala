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

import java.util.UUID

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.{ NonEmptyList, Validated }
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.{ AlgorithmExecutor, AlgorithmResults }
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.fp.runLater
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, ErrorJobResult, PfaJobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.AlgorithmSpec
import ch.chuv.lren.woken.messages.validation._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.service.FeaturesTableService
import ch.chuv.lren.woken.validation.{ FeaturesSplitter, PartioningQueries }
import com.typesafe.scalalogging.LazyLogging
import spray.json.JsValue

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.language.{ higherKinds, postfixOps }

object CrossValidationFlow {

  case class Job[F[_]: Effect](
      jobId: String,
      query: FeaturesQuery,
      metadata: List[VariableMetaData],
      splitter: FeaturesSplitter[F],
      featuresTableService: FeaturesTableService[F],
      algorithm: AlgorithmSpec,
      algorithmDefinition: AlgorithmDefinition
  ) {

    // Invariants
    assert(featuresTableService.table.table == query.dbTable)
  }

  private[CrossValidationFlow] case class FoldContext[R, F[_]: Effect](
      job: Job[F],
      response: R,
      partition: PartioningQueries,
      targetMetaData: VariableMetaData
  )

  private[CrossValidationFlow] case class FoldResult[F[_]](
      job: Job[F],
      scores: ScoringResult,
      validationResults: List[JsValue],
      groundTruth: List[JsValue],
      fold: Int,
      targetMetaData: VariableMetaData
  )

  private[CrossValidationFlow] case class CrossValidationScore[F[_]](
      job: Job[F],
      score: ScoringResult,
      foldScores: Map[Int, ScoringResult],
      validations: List[JsValue]
  )

  type FoldResults[F[_]] = List[FoldResult[F]]
}

case class CrossValidationFlow[F[_]: Effect](
    algorithmExecutor: AlgorithmExecutor[F],
    wokenWorker: WokenWorker[F]
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import CrossValidationFlow._

  def crossValidate(
      parallelism: Int
  ): Flow[Job[F], Option[(Job[F], Either[String, Score])], NotUsed] =
    Flow[Job[F]]
      .map { job =>
        logger.info(s"Validation spec: ${job.splitter.definition.validation}")

        job.splitter.splitFeatures(job.query).map((job, _))
      }
      .mapConcat(identity)
      .mapAsyncUnordered(parallelism) { f =>
        val (job, partition) = f
        runLater(localJobForFold(job, partition))
      }
      .mapAsync(parallelism)(r => runLater(handleFoldJobResponse(r)))
      .mapAsync(parallelism)(r => runLater(validateFold(r)))
      .mapAsync(parallelism)(r => runLater(scoreFoldValidationResponse(r)))
      .log("Fold result")
      .fold[FoldResults[F]](List[FoldResult[F]]()) { (l, r) =>
        l :+ r
      }
      .mapAsync(1) { foldResults =>
        runLater(scoreAll(foldResults.sortBy(_.fold).toNel))
      }
      .map { jobScoreOption =>
        jobScoreOption.map { crossValidationScore =>
          crossValidationScore.score.result match {
            case Right(score: VariableScore) =>
              crossValidationScore.job -> Right[String, Score](
                KFoldCrossValidationScore(
                  average = score,
                  folds = crossValidationScore.foldScores
                    .filter {
                      case (k, ScoringResult(Left(error))) =>
                        logger.warn(s"Fold $k failed with message $error")
                        false
                      case _ => true
                    }
                    .map {
                      case (k, ScoringResult(Right(score: VariableScore))) => (k, score)
                    }
                )
              )
            case Right(kFoldCrossValidationScore: KFoldCrossValidationScore) =>
              crossValidationScore.job -> Right(kFoldCrossValidationScore)

            case Left(error) =>
              logger.warn(s"Global score failed with message $error")
              crossValidationScore.job -> Left(error)
          }
        }
      // Aggregation of results from all folds

      }
      .log("Cross validation result")
      .named("crossValidate")

  private def targetMetadata(job: Job[F]): VariableMetaData =
    job.query.dbVariables.headOption
      .flatMap { v =>
        job.metadata.find(m => m.code == v)
      }
      .getOrElse(throw new Exception("Problem with variables' meta data!"))

  private def localJobForFold(
      job: Job[F],
      partition: PartioningQueries
  ): F[FoldContext[AlgorithmResults, F]] = {

    // Spawn a LocalCoordinatorActor for that one particular fold
    val jobId = UUID.randomUUID().toString

    val subJob = DockerJob(
      jobId = jobId,
      query = partition.trainingDatasetQuery,
      algorithmSpec = job.algorithm,
      algorithmDefinition = job.algorithmDefinition,
      metadata = job.metadata
    )

    algorithmExecutor
      .execute(subJob)
      .map(
        response =>
          FoldContext[AlgorithmResults, F](job = job,
                                           response = response,
                                           partition = partition,
                                           targetMetaData = targetMetadata(job))
      )
  }

  private def handleFoldJobResponse(
      context: FoldContext[AlgorithmResults, F]
  ): F[FoldContext[ValidationQuery, F]] = {
    val queryF: F[ValidationQuery] = context.response match {
      case AlgorithmResults(_, List(pfa: PfaJobResult)) =>
        // Prepare the results for validation
        logger.info("Received result from local method.")
        // Take the raw model, as model contains runtime-inserted validations which are not yet compliant with PFA / Avro spec
        val model     = pfa.modelWithoutValidation
        val partition = context.partition

        logger.info(
          s"Send a validation work for fold ${partition.fold} to validation worker"
        )

        val tableService                   = context.job.featuresTableService
        val independentVarsFromTestDataset = partition.testDatasetQuery.independentVariablesOnly

        tableService.features(independentVarsFromTestDataset).map { queryResults =>
          ValidationQuery(partition.fold, model, queryResults._2.toList, context.targetMetaData)
        }

      case AlgorithmResults(_, List(error: ErrorJobResult)) =>
        val message =
          s"Error on cross validation job ${error.jobId} during fold ${context.partition.fold}" +
            s" on variable ${context.targetMetaData.code}: ${error.error}"
        logger.error(message)
        // On training fold fails, we notify supervisor and we stop
        Effect[F].raiseError(new IllegalStateException(message))

      case AlgorithmResults(_, unhandled) =>
        val message =
          s"Error on cross validation job ${context.job.jobId} during fold ${context.partition.fold}" +
            s" on variable ${context.targetMetaData.code}: Unhandled response from CoordinatorActor: $unhandled"
        logger.error(message)
        // On training fold fails, we notify supervisor and we stop
        Effect[F].raiseError(new IllegalStateException(message))
    }

    queryF.map(
      r =>
        FoldContext[ValidationQuery, F](job = context.job,
                                        response = r,
                                        partition = context.partition,
                                        targetMetaData = context.targetMetaData)
    )
  }

  private def validateFold(
      context: FoldContext[ValidationQuery, F]
  ): F[FoldContext[ValidationResult, F]] = {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    val validationQuery              = context.response
    val validationResult             = wokenWorker.validate(validationQuery)
    validationResult.map(
      r =>
        FoldContext[ValidationResult, F](job = context.job,
                                         response = r,
                                         partition = context.partition,
                                         targetMetaData = context.targetMetaData)
    )
  }

  private def scoreFoldValidationResponse(
      context: FoldContext[ValidationResult, F]
  ): F[FoldResult[F]] = {
    import cats.syntax.list._

    val fold = context.partition.fold
    val resultsV: Validation[NonEmptyList[JsValue]] = Validated
      .fromEither(context.response.result.leftMap(e => NonEmptyList(e, Nil)))
      .andThen { v: List[JsValue] =>
        Validated.fromOption(v.toNel, NonEmptyList(s"No results on fold $fold", Nil))
      }

    val tableService                 = context.job.featuresTableService
    val dependentVarsFromTestDataset = context.partition.testDatasetQuery.dependentVariablesOnly
    val groundTruthF: F[Validation[NonEmptyList[JsValue]]] =
      tableService.features(dependentVarsFromTestDataset).map { queryResults =>
        val values = queryResults._2
          .map { jsObj =>
            jsObj.fields.values.toList match {
              case v :: Nil => v
              case _ =>
                throw new IllegalStateException("Expected only one value for ground truth")
            }
          }
          .toList
          .toNel
        Validated.fromOption(values, NonEmptyList(s"Empty test set on fold $fold", Nil))
      }

    def performScoring(algorithmOutput: NonEmptyList[JsValue],
                       groundTruth: NonEmptyList[JsValue]): F[FoldResult[F]] = {
      implicit val askTimeout: Timeout = Timeout(5 minutes)
      val scoringQuery                 = ScoringQuery(algorithmOutput, groundTruth, context.targetMetaData)
      logger.info(s"scoringQuery: $scoringQuery")
      wokenWorker
        .score(scoringQuery)
        .map(
          s =>
            FoldResult(
              job = context.job,
              scores = s,
              validationResults = algorithmOutput.toList,
              groundTruth = groundTruth.toList,
              fold = fold,
              targetMetaData = context.targetMetaData
          )
        )
    }

    groundTruthF.flatMap { groundTruthV =>
      val r: F[FoldResult[F]] = ((resultsV, groundTruthV) mapN performScoring).valueOr { e =>
        val errorMsg = e.toList.mkString(",")
        logger.error(s"Cannot perform scoring on $context: $errorMsg")
        Effect[F].raiseError(new Exception(errorMsg))
      }
      r
    }
  }

  private def scoreAll(
      foldResultsOption: Option[NonEmptyList[FoldResult[F]]]
  ): F[Option[CrossValidationScore[F]]] = {

    import cats.syntax.list._

    foldResultsOption.map { foldResults =>
      val foldResultList = foldResults.toList
      val validations    = foldResultList.flatMap(_.validationResults)
      val groundTruths   = foldResultList.flatMap(_.groundTruth)
      val foldScores = foldResultList.map { s =>
        s.fold -> s.scores
      }.toMap

      val job            = foldResults.head.job
      val targetMetaData = foldResults.head.targetMetaData

      (validations.toNel, groundTruths.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val askTimeout: Timeout = Timeout(5 minutes)
          wokenWorker
            .score(ScoringQuery(r, gt, targetMetaData))
            .map { score =>
              CrossValidationScore(job = job,
                                   score = score,
                                   foldScores = foldScores,
                                   validations = validations)
            }
        case (r, gt) =>
          val message =
            s"Final reduce for cross-validation uses empty datasets: Validations = $r, ground truths = $gt"
          logger.error(message)
          Effect[F].pure(
            CrossValidationScore(job = job,
                                 score = ScoringResult(Left(message)),
                                 foldScores = foldScores,
                                 validations = validations)
          )

      }
    }
  }.sequence

}
