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
import akka.actor.{ ActorContext, ActorRef }
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.event.Logging
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.{ NonEmptyList, Validated }
import cats.implicits._
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.features.QueryOffset
import ch.chuv.lren.woken.core.CoordinatorActor
import ch.chuv.lren.woken.core.model.{ DockerJob, ErrorJobResult, PfaJobResult }
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesDAL
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
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private val log = Logging(context.system, getClass)

  private lazy val mediator: ActorRef = DistributedPubSub(context.system).mediator

  import CrossValidationFlow.{ CrossValidationScore, FoldContext, FoldResult, Job }

  def crossValidate(parallelism: Int): Flow[Job, Option[(Job, Either[String, Score])], NotUsed] =
    Flow[Job]
      .map { job =>
        val validation = job.validation
        val foldCount  = validation.parametersAsMap("k").toInt
        val featuresQuery =
          job.query
            .filterNulls(job.algorithmDefinition.variablesCanBeNull,
                         job.algorithmDefinition.covariablesCanBeNull)
            .features(job.inputTable, None)

        log.info(s"List of folds: $foldCount")

        // TODO For now only kfold cross-validation
        val crossValidation =
          KFoldCrossValidation(featuresQuery, foldCount, featuresDatabase)

        assert(
          crossValidation.partition.size == foldCount,
          s"Excepted number of folds ($foldCount) to match the number of partitions (${crossValidation.partition.size})"
        )

        // For every fold
        crossValidation.partition.toList.map((job, crossValidation, _))
      }
      .mapConcat(identity)
      .mapAsyncUnordered(parallelism) { f =>
        val (job, crossValidation, (fold, offset)) = f
        localJobForFold(job, offset, fold, crossValidation)
      }
      .mapAsync(parallelism)(handleFoldJobResponse)
      .mapAsync(parallelism)(validateFold)
      .mapAsync(parallelism)(scoreFoldValidationResponse)
      .log("Fold result")
      .fold[List[FoldResult]](List[FoldResult]()) { (l, r) =>
        l :+ r
      }
      .mapAsync(1) { foldResults =>
        if (foldResults.isEmpty) throw new Exception("No fold results received")
        scoreAll(foldResults.sortBy(_.fold).toNel)
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
                        log.warning(s"Fold $k failed with message $error")
                        false
                      case _ => true
                    }
                    .map {
                      case (k, ScoringResult(Right(score: VariableScore))) => (k, score)
                    }
                )
              )
            case Left(error) =>
              log.warning(s"Global score failed with message $error")
              crossValidationScore.job -> Left(error)
          }
        }
      // Aggregation of results from all folds

      }
      .log("Cross validation result")
      .named("crossValidate")

  private def targetMetadata(job: Job): VariableMetaData = {
    import ch.chuv.lren.woken.core.features.Queries._
    job.query.dbVariables.headOption
      .flatMap { v =>
        job.metadata.find(m => m.code == v)
      }
      .getOrElse(throw new Exception("Problem with variables' meta data!"))
  }

  private def localJobForFold(
      job: Job,
      offset: QueryOffset,
      fold: Int,
      validation: KFoldCrossValidation
  ): Future[FoldContext[CoordinatorActor.Response]] = {

    // Spawn a LocalCoordinatorActor for that one particular fold
    val jobId = UUID.randomUUID().toString
    val featuresQuery =
      job.query
        .filterNulls(job.algorithmDefinition.variablesCanBeNull,
                     job.algorithmDefinition.covariablesCanBeNull)
        .features(job.inputTable, Some(offset))

    val subJob = DockerJob(
      jobId = jobId,
      dockerImage = job.algorithmDefinition.dockerImage,
      inputDb = job.inputDb,
      query = featuresQuery,
      algorithmSpec = job.query.algorithm,
      metadata = job.metadata
    )

    executeJobAsync(subJob).map(
      response =>
        FoldContext[CoordinatorActor.Response](job = job,
                                               response = response,
                                               fold = fold,
                                               targetMetaData = targetMetadata(job),
                                               validation = validation)
    )
  }

  private def handleFoldJobResponse(
      context: FoldContext[CoordinatorActor.Response]
  ): Future[FoldContext[ValidationQuery]] =
    (context.response match {
      case CoordinatorActor.Response(_, List(pfa: PfaJobResult), _) =>
        // Prepare the results for validation
        log.info("Received result from local method.")
        val model    = pfa.model
        val fold     = context.fold
        val testData = context.validation.getTestSet(fold)._1

        log.info(
          s"Send a validation work for fold $fold to validation worker"
        )
        val validationQuery = ValidationQuery(fold, model, testData, context.targetMetaData)
        Future(validationQuery)

      case CoordinatorActor.Response(_, List(error: ErrorJobResult), _) =>
        val message =
          s"Error on cross validation job ${error.jobId} during fold ${context.fold}" +
            s" on variable ${context.targetMetaData.code}: ${error.error}"
        log.error(message)
        // On training fold fails, we notify supervisor and we stop
        Future.failed[ValidationQuery](new IllegalStateException(message))

      case CoordinatorActor.Response(_, unhandled, _) =>
        val message =
          s"Error on cross validation job ${context.job.jobId} during fold ${context.fold}" +
            s" on variable ${context.targetMetaData.code}: Unhandled response from CoordinatorActor: $unhandled"
        log.error(message)
        // On training fold fails, we notify supervisor and we stop
        Future.failed[ValidationQuery](new IllegalStateException(message))
    }).map(
      r =>
        FoldContext[ValidationQuery](job = context.job,
                                     response = r,
                                     fold = context.fold,
                                     targetMetaData = context.targetMetaData,
                                     validation = context.validation)
    )

  private def validateFold(
      context: FoldContext[ValidationQuery]
  ): Future[FoldContext[ValidationResult]] = {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    val validationQuery              = context.response
    val validationResult             = remoteValidate(validationQuery)
    validationResult.map(
      r =>
        FoldContext[ValidationResult](job = context.job,
                                      response = r,
                                      fold = context.fold,
                                      targetMetaData = context.targetMetaData,
                                      validation = context.validation)
    )
  }

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
      val scoringQuery                 = ScoringQuery(algorithmOutput, groundTruth, context.targetMetaData)
      log.info(s"scoringQuery: $scoringQuery")
      remoteScore(scoringQuery)
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

    foldResultV.valueOr(e => {
      val errorMsg = e.toList.mkString(",")
      log.error(s"Cannot perform scoring on $context: $errorMsg")
      Future.failed(new Exception(errorMsg))
    })
  }

  private def scoreAll(
      foldResultsOption: Option[NonEmptyList[FoldResult]]
  ): Future[Option[CrossValidationScore]] = {

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
          remoteScore(ScoringQuery(r, gt, targetMetaData))
            .map { score =>
              CrossValidationScore(job = job,
                                   score = score,
                                   foldScores = foldScores,
                                   validations = validations)
            }
        case (r, gt) =>
          val message =
            s"Final reduce for cross-validation uses empty datasets: Validations = $r, ground truths = $gt"
          log.error(message)
          Future(
            CrossValidationScore(job = job,
                                 score = ScoringResult(Left(message)),
                                 foldScores = foldScores,
                                 validations = validations)
          )

      }
    }
  }.sequence

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
