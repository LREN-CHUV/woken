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

package eu.hbp.mip.woken.core

import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSelection, LoggingFSM, Props }
import akka.pattern.ask
import akka.util
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.api._
import eu.hbp.mip.woken.backends.{ DockerJob, QueryOffset }
import eu.hbp.mip.woken.config.WokenConfig.defaultSettings.{ dockerImage, isPredictive }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.core.validation.{ KFoldCrossValidation, ValidationPoolManager }
import eu.hbp.mip.woken.messages.external.{
  Algorithm,
  ExperimentQuery,
  MiningQuery,
  Validation => ApiValidation
}
import eu.hbp.mip.woken.messages.validation._
import eu.hbp.mip.woken.meta.{ MetaDataProtocol, VariableMetaData }
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{ JsString, _ }

import scala.concurrent.{ Await, Future }
import scala.util.Random

/**
  * We use the companion object to hold all the messages that the ``ExperimentActor`` receives.
  */
object ExperimentActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: ExperimentQuery,
      metadata: JsObject
  )

  case class Start(job: Job) extends RestMessage {
    import ApiJsonSupport._
    import spray.httpx.SprayJsonSupport._
    implicit val jobFormat: RootJsonFormat[Job] = jsonFormat5(Job.apply)
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  case object Done

  // Output messages: JobResult containing the experiment PFA
  type Result = eu.hbp.mip.woken.core.model.JobResult
  val Result = eu.hbp.mip.woken.core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {

    import DefaultJsonProtocol._
    import spray.httpx.SprayJsonSupport._

    override def marshaller: ToResponseMarshaller[ErrorResponse] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(
        jsonFormat1(ErrorResponse)
      )
  }

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(new ExperimentActor(coordinatorConfig))

  import JobResult._

  implicit val resultFormat: JsonFormat[Result]                   = JobResult.jobResultFormat
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)
}

/** FSM States and internal data */
object ExperimentStates {
  import ExperimentActor.Job

  // FSM States

  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM Data

  sealed trait ExperimentData

  case object Uninitialized extends ExperimentData

  // TODO: results should be Map[Algorithm, \/[String,String]] to keep notion of error responses
  case class PartialExperimentData(
      job: Job,
      replyTo: ActorRef,
      results: Map[Algorithm, String],
      algorithms: Seq[Algorithm]
  ) extends ExperimentData {
    def isComplete: Boolean = results.size == algorithms.length
  }

  case class CompletedExperimentData(
      job: Job,
      replyTo: ActorRef,
      results: Map[Algorithm, String],
      algorithms: Seq[Algorithm]
  ) extends ExperimentData

  object CompletedExperimentData {
    def apply(from: PartialExperimentData): CompletedExperimentData =
      CompletedExperimentData(job = from.job,
                              replyTo = from.replyTo,
                              results = from.results,
                              algorithms = from.algorithms)
  }
}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val coordinatorConfig: CoordinatorConfig)
    extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[ExperimentStates.State, ExperimentStates.ExperimentData] {

  import ExperimentActor._
  import ExperimentStates._

  log.info("Experiment started")

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(Start(job), _) if job.query.algorithms.nonEmpty =>
      val replyTo     = sender()
      val algorithms  = job.query.algorithms
      val validations = job.query.validations

      log.info("Start new experiment job")
      log.info(s"List of algorithms: ${algorithms.mkString(",")}")

      // Spawn an AlgorithmActor for every algorithm
      for (a <- algorithms) {
        val jobId = UUID.randomUUID().toString
        val miningQuery = MiningQuery(
          variables = job.query.variables,
          covariables = job.query.covariables,
          grouping = job.query.grouping,
          filters = job.query.filters,
          algorithm = a
        )
        val subJob = AlgorithmActor.Job(jobId,
                                        job.inputDb,
                                        job.inputTable,
                                        miningQuery,
                                        job.metadata,
                                        validations)
        val worker = context.actorOf(
          AlgorithmActor.props(coordinatorConfig),
          AlgorithmActor.actorName(subJob)
        )
        worker ! AlgorithmActor.Start(subJob)
      }

      goto(WaitForWorkers) using PartialExperimentData(job, replyTo, Map.empty, algorithms)
  }

  when(WaitForWorkers) {
    case Event(AlgorithmActor.ResultResponse(algorithm, algorithmResults),
               previousExperimentData: PartialExperimentData) =>
      log.info(s"Received algorithm result $algorithmResults")
      val results        = previousExperimentData.results + (algorithm -> algorithmResults)
      val experimentData = previousExperimentData.copy(results = results)
      if (experimentData.isComplete) {
        log.info("All results received")
        goto(Reduce) using CompletedExperimentData(experimentData)
      } else {
        log.info(
          s"Received ${experimentData.results.size} results out of ${experimentData.algorithms.size}"
        )
        stay using experimentData
      }

    case Event(AlgorithmActor.ErrorResponse(algorithm, errorMessage),
               previousExperimentData: PartialExperimentData) =>
      log.error(s"Algorithm ${algorithm.code} returned with error $errorMessage")
      val results        = previousExperimentData.results + (algorithm -> errorMessage)
      val experimentData = previousExperimentData.copy(results = results)
      if (experimentData.isComplete) {
        log.info("All results received")
        goto(Reduce) using CompletedExperimentData(experimentData)
      } else {
        log.info(
          s"Received ${experimentData.results.size} results out of ${experimentData.algorithms.size}"
        )
        stay using experimentData
      }
  }

  when(Reduce) {
    case Event(Done, experimentData: CompletedExperimentData) =>
      log.info("Experiment - build final response")

      //TODO WP3 Save the results in results DB

      // Concatenate results while respecting received algorithms order
      val output = JsArray(
        experimentData.algorithms
          .map(
            a =>
              JsObject("code" -> JsString(a.code),
                       "name" -> JsString(a.name),
                       "data" -> JsonParser(experimentData.results(a)))
          )
          .toVector
      )

      experimentData.replyTo ! coordinatorConfig.jobResultsFactory(
        Seq(
          JobResult(
            jobId = experimentData.job.jobId,
            node = "",
            timestamp = OffsetDateTime.now(),
            shape = "pfa_json",
            function = "",
            // TODO: early serialisation to Json, keep Json type?
            data = Some(output.compactPrint),
            error = None
          )
        )
      )
      log.info("Stopping...")
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}

/**
  * We use the companion object to hold all the messages that the ``AlgorithmActor``
  * receives.
  */
object AlgorithmActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: MiningQuery,
      metadata: JsObject,
      validations: Seq[ApiValidation]
  )
  case class Start(job: Job)
  case object Done

  case class ResultResponse(algorithm: Algorithm, data: String)
  case class ErrorResponse(algorithm: Algorithm, message: String)

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(classOf[AlgorithmActor], coordinatorConfig)

  def actorName(job: Job): String =
    s"AlgorithmActor_job_${job.jobId}_algo_${job.query.algorithm.code}"

}

/** FSM States and internal data */
object AlgorithmStates {
  import AlgorithmActor.Job

  // FSM States
  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM Data
  sealed trait AlgorithmData

  case object Uninitialized extends AlgorithmData

  case class PartialAlgorithmData(job: Job,
                                  replyTo: ActorRef,
                                  model: Option[String],
                                  results: Map[ApiValidation, String],
                                  validationCount: Int)
      extends AlgorithmData {

    def isComplete: Boolean = (results.size == validationCount) && model.isDefined
  }

  case class CompleteAlgorithmData(job: Job,
                                   replyTo: ActorRef,
                                   model: String,
                                   results: Map[ApiValidation, String],
                                   validationCount: Int)
      extends AlgorithmData

  object CompleteAlgorithmData {
    def apply(from: PartialAlgorithmData): CompleteAlgorithmData =
      new CompleteAlgorithmData(job = from.job,
                                replyTo = from.replyTo,
                                model = from.model.get,
                                results = from.results,
                                validationCount = from.validationCount)
  }
}

class AlgorithmActor(val coordinatorConfig: CoordinatorConfig)
    extends Actor
    with ActorLogging
    with LoggingFSM[AlgorithmStates.State, AlgorithmStates.AlgorithmData] {

  import AlgorithmActor._
  import AlgorithmStates._

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(AlgorithmActor.Start(job), _) =>
      val replyTo     = sender()
      val algorithm   = job.query.algorithm
      val validations = if (isPredictive(algorithm.code)) job.validations else List()

      log.info(s"Start job for algorithm ${algorithm.code}")
      log.info(s"List of validations: ${validations.size}")

      // Spawn a LocalCoordinatorActor
      {
        val jobId = UUID.randomUUID().toString
        val subJob =
          DockerJob(jobId,
                    dockerImage(algorithm.code),
                    job.inputDb,
                    job.inputTable,
                    job.query,
                    job.metadata)
        val worker = context.actorOf(
          CoordinatorActor.props(coordinatorConfig),
          CoordinatorActor.actorName(subJob)
        )
        worker ! CoordinatorActor.Start(subJob)
      }

      // Spawn a CrossValidationActor for every validation
      for (v <- validations) {
        val jobId = UUID.randomUUID().toString
        val subJob =
          CrossValidationActor.Job(jobId, job.inputDb, job.inputTable, job.query, job.metadata, v)
        val validationWorker = context.actorOf(
          CrossValidationActor.props(coordinatorConfig),
          CrossValidationActor.actorName(subJob)
        )
        validationWorker ! CrossValidationActor.Start(subJob)
      }

      goto(WaitForWorkers) using PartialAlgorithmData(job, replyTo, None, Map(), validations.size)
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), previousData: PartialAlgorithmData) =>
      // TODO - LC: why receiving one model is enough to stop this actor? If there are several validations, there should be as many models?
      // TODO: not clear where this JsonMessage comes from. Need types...
      val data = previousData.copy(model = Some(pfa.compactPrint))
      if (data.isComplete) {
        log.info("Received PFA result, algorithm processing complete")
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        log.info(s"Received PFA result")
        if (data.model.isEmpty)
          log.info("Still waiting for PFA model")
        if (data.results.size < data.validationCount)
          log.info(s"Received ${data.results.size} out of ${data.validationCount}")
        stay using data
      }

    case Event(CoordinatorActor.ErrorResponse(message), previousData: PartialAlgorithmData) =>
      log.error(
        s"Execution of algorithm ${previousData.job.query.algorithm.code} failed with message: $message"
      )
      // We cannot trained the model we notify supervisor and we stop
      context.parent ! ErrorResponse(previousData.job.query.algorithm, message)
      log.info("Stopping...")
      stop

    case Event(CrossValidationActor.ResultResponse(validation, results),
               previousData: PartialAlgorithmData) =>
      val data = previousData.copy(results = previousData.results + (validation -> results))
      if (data.isComplete) {
        log.info("Received validation result, algorithm processing complete")
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        log.info("Received validation result")
        if (data.model.isEmpty)
          log.info("Waiting for missing PFA model...")
        if (data.results.size < data.validationCount)
          log.info(s"Received ${data.results.size} out of ${data.validationCount}")
        stay using data
      }

    case Event(CrossValidationActor.ErrorResponse(validation, message),
               previousData: PartialAlgorithmData) =>
      log.error(
        s"Validation of algorithm ${previousData.job.query.algorithm.code} returned with error : $message"
      )
      val data = previousData.copy(results = previousData.results + (validation -> message))
      if (data.isComplete) {
        log.info("Received validation error, algorithm processing complete")
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        if (data.model.isEmpty)
          log.info("Waiting for missing PFA model...")
        if (data.results.size < data.validationCount)
          log.info(s"Received ${data.results.size} out of ${data.validationCount}")
        stay using data
      }
  }

  when(Reduce) {
    case Event(Done, data: CompleteAlgorithmData) =>
      val validations = JsArray(
        data.results
          .map({
            case (key, value) =>
              JsObject("code" -> JsString(key.code),
                       "name" -> JsString(key.name),
                       "data" -> JsonParser(value))
          })
          .toVector
      )

      // TODO Do better by merging JsObject (not yet supported by Spray...)
      val pfa = data.model
        .replaceFirst("\"cells\":\\{",
                      "\"cells\":{\"validations\":" + validations.compactPrint + ",")

      data.replyTo ! AlgorithmActor.ResultResponse(data.job.query.algorithm, pfa)
      log.info("Stopping...")
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}

/**
  * We use the companion object to hold all the messages that the ``ValidationActor``
  * receives.
  */
object CrossValidationActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: MiningQuery,
      metadata: JsObject,
      validation: ApiValidation
  )
  case class Start(job: Job)
  case object Done

  // Output Messages
  case class ResultResponse(validation: ApiValidation, data: String)
  case class ErrorResponse(validation: ApiValidation, message: String)

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(classOf[CrossValidationActor], coordinatorConfig)

  def actorName(job: Job): String =
    s"CrossValidationActor_job_${job.jobId}_algo_${job.query.algorithm.code}"

}

/** FSM States and internal data */
object CrossValidationStates {
  import CrossValidationActor.Job

  // FSM States
  sealed trait State

  case object WaitForNewJob extends State

  case object WaitForWorkers extends State

  case object Reduce extends State

  type Fold = String

  // FSM Data
  trait StateData {
    def job: Job
  }

  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class WaitForWorkersState(job: Job,
                                 replyTo: ActorRef,
                                 validation: KFoldCrossValidation,
                                 workers: Map[ActorRef, Fold],
                                 foldCount: Int,
                                 targetMetaData: VariableMetaData,
                                 average: (List[String], List[String]),
                                 results: Map[String, ScoringResult])
      extends StateData

  case class ReduceData(job: Job,
                        replyTo: ActorRef,
                        targetMetaData: VariableMetaData,
                        average: (List[String], List[String]),
                        results: Map[String, ScoringResult])
      extends StateData

}

class CrossValidationActor(val coordinatorConfig: CoordinatorConfig)
    extends Actor
    with ActorLogging
    with LoggingFSM[CrossValidationStates.State, CrossValidationStates.StateData] {

  import CrossValidationActor._
  import CrossValidationStates._

//  def adjust[A, B](m: Map[A, B], k: A)(f: B => B): Map[A, B] = m.updated(k, f(m(k)))

  def nextValidationActor: Option[ActorSelection] = {
    val validationPool = ValidationPoolManager.validationPool
    if (validationPool.isEmpty) None
    else
      Some(
        context.actorSelection(validationPool.toList(Random.nextInt(validationPool.size)))
      )
  }

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(Start(job), _) =>
      val replyTo    = sender()
      val algorithm  = job.query.algorithm
      val validation = job.validation

      log.info(s"List of folds: ${validation.parameters("k")}")

      val foldCount = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val crossValidation = KFoldCrossValidation(job, foldCount)

      assert(crossValidation.partition.size == foldCount)

      // For every fold
      val workers = crossValidation.partition.map {
        case (fold, (s, n)) =>
          // Spawn a LocalCoordinatorActor for that one particular fold
          val jobId = UUID.randomUUID().toString
          val subJob = DockerJob(
            jobId = jobId,
            dockerImage = dockerImage(algorithm.code),
            inputDb = job.inputDb,
            inputTable = job.inputTable,
            query = job.query,
            metadata = job.metadata,
            shadowOffset = Some(QueryOffset(s, n))
          )

          val worker = context.actorOf(
            CoordinatorActor.props(coordinatorConfig)
          )
          //workers(worker) = fold
          worker ! CoordinatorActor.Start(subJob)

          (worker, fold)
      }

      // TODO: move this code in a better place, test it
      import FunctionsInOut._
      import MetaDataProtocol._
      val targetMetaData: VariableMetaData = job.metadata
        .convertTo[Map[String, VariableMetaData]]
        .get(job.query.dbVariables.head) match {
        case Some(v: VariableMetaData) => v
        case None                      => throw new Exception("Problem with variables' meta data!")
      }

      goto(WaitForWorkers) using WaitForWorkersState(job = job,
                                                     replyTo = replyTo,
                                                     validation = crossValidation,
                                                     workers = workers,
                                                     targetMetaData = targetMetaData,
                                                     average = (Nil, Nil),
                                                     results = Map(),
                                                     foldCount = foldCount)
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), data: WaitForWorkersState) =>
      // Validate the results
      log.info("Received result from local method.")
      val model    = pfa.toString()
      val fold     = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      val sendTo = nextValidationActor
      log.info(s"Send a validation work for fold $fold to pool agent: $sendTo")
      sendTo.fold {
        context.parent ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                            "Validation system not available")
        log.info("Stopping...")
        stop
      } { validationActor =>
        validationActor ! ValidationQuery(fold, model, testData, data.targetMetaData)
        stay
      }

    case Event(ValidationResult(fold, targetMetaData, results), data: WaitForWorkersState) =>
      log.info(s"Received validation results for fold $fold.")
      // Score the results
      val groundTruth = data.validation
        .getTestSet(fold)
        ._2
        .map(x => x.asJsObject.fields.toList.head._2.compactPrint)
      log.info(s"Ground truth: $groundTruth")

      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (results.toNel, groundTruth.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout: util.Timeout = Timeout(5 minutes)
          // TODO: replace ask pattern by 1) sending a ScoringQuery and handling ScoringResult in this state and
          // 2) start a timer to handle timeouts. A bit tricky as we need to keep track of several ScoringQueries at once
          val futureO: Option[Future[_]] =
            nextValidationActor.map(_ ? ScoringQuery(r, gt, data.targetMetaData))

          futureO.fold {
            log.error("Validation system not connected")
            data.replyTo ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                              "Validation system not connected")
            stop
          } { future =>
            log.info("Waiting for scoring results...")
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            // TODO To be improved with new Spark integration - LC: what was that about?
            // Update the average score
            val updatedAverage = (data.average._1 ::: results, data.average._2 ::: groundTruth)
            val updatedResults = data.results + (fold -> scores)

            // TODO - LC: use updatedAverage in the next step
            // If we have validated all the fold we finish!
            if (updatedResults.size == data.foldCount) {
              log.info("Received the scores for each folds, moving on to final reduce step")
              goto(Reduce) using ReduceData(job = data.job,
                                            replyTo = data.replyTo,
                                            targetMetaData = data.targetMetaData,
                                            average = updatedAverage,
                                            results = updatedResults)
            } else {
              log.info(
                s"Waiting for more scoring results as we have received ${updatedResults.size} scores and there are ${data.foldCount} folds"
              )
              stay using data.copy(average = updatedAverage, results = updatedResults)
            }
          }

        case (Some(_), None) =>
          val message = s"No results on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
        case (None, Some(_)) =>
          val message = s"Empty test set on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
        case _ =>
          val message = s"No data selected during fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
      }

    case Event(ValidationError(message), data: WaitForWorkersState) =>
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      log.info("Stopping...")
      stop

    case Event(Error(message), data: WaitForWorkersState) =>
      log.error(message)
      // On training fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      log.info("Stopping...")
      stop
  }

  when(Reduce) {
    case Event(Done, data: ReduceData) =>
      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (data.average._1.toNel, data.average._2.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout: util.Timeout = Timeout(5 minutes)

          // TODO: replace ask pattern by 1) sending a ScoringQuery and handling ScoringResult in this state and
          // 2) start a timer to handle timeouts
          val futureO: Option[Future[_]] =
            nextValidationActor.map(_ ? ScoringQuery(r, gt, data.targetMetaData))
          futureO.fold(
            data.replyTo ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                              "Validation system not connected")
          ) { future =>
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            // Aggregation of results from all folds
            val jsonValidation = JsObject(
              "type"    -> JsString("KFoldCrossValidation"),
              "average" -> scores.scores,
              "folds"   -> new JsObject(data.results.mapValues(s => s.scores))
            )

            data.replyTo ! CrossValidationActor.ResultResponse(data.job.validation,
                                                               jsonValidation.compactPrint)
          }
        case _ =>
          val message = s"Final reduce for cross-validation uses empty datasets"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      }
      log.info("Stopping...")
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}
