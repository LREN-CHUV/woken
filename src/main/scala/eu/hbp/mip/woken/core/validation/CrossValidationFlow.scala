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

package eu.hbp.mip.woken.core.validation

import java.util.UUID

import akka.NotUsed
import akka.actor.{ ActorContext, ActorSelection }
import akka.event.Logging
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.syntax.list._
import eu.hbp.mip.woken.backends.{ DockerJob, QueryOffset }
import eu.hbp.mip.woken.config.AlgorithmDefinition
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig }
import eu.hbp.mip.woken.core.model.{ ErrorJobResult, PfaJobResult }
import eu.hbp.mip.woken.messages.external.{ MiningQuery, ValidationSpec }
import eu.hbp.mip.woken.messages.validation.{
  ScoringQuery,
  ScoringResult,
  ValidationQuery,
  ValidationResult
}
import eu.hbp.mip.woken.meta.{ VariableMetaData, VariableMetaDataProtocol }
import spray.json.{ JsObject, JsString }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

object CrossValidationFlow {

  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: MiningQuery,
      metadata: JsObject,
      validation: ValidationSpec,
      algorithmDefinition: AlgorithmDefinition
  )

  private[CrossValidationFlow] case class FoldContext[R](
      job: Job,
      response: R,
      fold: String,
      targetMetaData: VariableMetaData,
      validation: KFoldCrossValidation
  )

  private[CrossValidationFlow] case class FoldResult(
      job: Job,
      scores: ScoringResult,
      validationResults: List[String],
      groundTruth: List[String],
      fold: String,
      targetMetaData: VariableMetaData
  )

  private[CrossValidationFlow] case class CrossValidationScore(
      job: Job,
      score: ScoringResult,
      foldScores: Map[String, ScoringResult],
      validations: List[String]
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
  ): Flow[CrossValidationFlow.Job, (CrossValidationFlow.Job, JsObject), NotUsed] =
    Flow[CrossValidationFlow.Job]
      .map { job =>
        val validation = job.validation
        val foldCount  = validation.parametersAsMap("k").toInt

        log.info(s"List of folds: $foldCount")

        // TODO For now only kfold cross-validation
        val crossValidation =
          KFoldCrossValidation(job, foldCount, coordinatorConfig.featuresDatabase)

        assert(crossValidation.partition.size == foldCount)

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
      .scan[List[FoldResult]](List[FoldResult]()) { (l, r) =>
        l :+ r
      }
      .mapAsync(1) { foldResults =>
        scoreAll(foldResults.sortBy(_.fold.toInt))
      }
      .map { crossValidationScore =>
        // Aggregation of results from all folds
        (crossValidationScore.job,
         JsObject(
           "type"    -> JsString("KFoldCrossValidation"),
           "average" -> crossValidationScore.score.scores,
           "folds"   -> new JsObject(crossValidationScore.foldScores.mapValues(s => s.scores))
         ))

      }
      .named("crossValidate")

  private def targetMetadata(job: Job) = {
    // TODO: move this code in a better place, test it
    import eu.hbp.mip.woken.core.model.Queries._
    import VariableMetaDataProtocol._
    val targetMetaData: VariableMetaData = job.metadata
      .convertTo[Map[String, VariableMetaData]]
      .get(job.query.dbVariables.head) match {
      case Some(v: VariableMetaData) => v
      case None                      => throw new Exception("Problem with variables' meta data!")
    }
    targetMetaData
  }

  private def localJobForFold(
      job: Job,
      s: Int,
      n: Int,
      fold: String,
      validation: KFoldCrossValidation
  ): Future[FoldContext[CoordinatorActor.Response]] = {

    // Spawn a LocalCoordinatorActor for that one particular fold
    val jobId = UUID.randomUUID().toString
    val subJob = DockerJob(
      jobId = jobId,
      dockerImage = job.algorithmDefinition.dockerImage,
      inputDb = job.inputDb,
      inputTable = job.inputTable,
      query = job.query,
      metadata = job.metadata,
      shadowOffset = Some(QueryOffset(s, n))
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
        val testData = context.validation.getTestSet(fold)._1.map(d => d.compactPrint)

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

    val results     = context.response.outputData
    val groundTruth = context.validation.groundTruth(context.fold)
    log.info(s"Ground truth: $groundTruth")

    (results.toNel, groundTruth.toNel) match {
      case (Some(r), Some(gt)) =>
        implicit val askTimeout: Timeout = Timeout(5 minutes)
        (scoringActor ? ScoringQuery(r, gt, context.targetMetaData))
          .mapTo[ScoringResult]
          .map(
            s =>
              FoldResult(job = context.job,
                         scores = s,
                         validationResults = results,
                         groundTruth = groundTruth,
                         fold = context.fold,
                         targetMetaData = context.targetMetaData)
          )

      case (Some(_), None) =>
        val message = s"No results on fold ${context.fold}"
        log.error(message)
        Future.failed[FoldResult](new IllegalStateException(message))

      case (None, Some(_)) =>
        val message = s"Empty test set on fold ${context.fold}"
        log.error(message)
        Future.failed[FoldResult](new IllegalStateException(message))

      case _ =>
        val message = s"No data selected during fold ${context.fold}"
        log.error(message)
        Future.failed[FoldResult](new IllegalStateException(message))
    }
  }

  private def scoreAll(foldResults: Seq[FoldResult]): Future[CrossValidationScore] = {

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
      case _ =>
        val message = s"Final reduce for cross-validation uses empty datasets"
        log.error(message)
        Future.failed[CrossValidationScore](new IllegalStateException(message))
    }

  }

}
