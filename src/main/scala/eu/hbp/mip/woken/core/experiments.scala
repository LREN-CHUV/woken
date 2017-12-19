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

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSelection, FSM, LoggingFSM, Props }
import akka.pattern.ask
import akka.util
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.backends.{ DockerJob, QueryOffset }
import eu.hbp.mip.woken.core.commands.JobCommands.{ StartCoordinatorJob, StartExperimentJob }
import eu.hbp.mip.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import eu.hbp.mip.woken.core.model.{
  ErrorJobResult,
  JobResult,
  PfaExperimentJobResult,
  PfaJobResult
}
import eu.hbp.mip.woken.core.validation.{ KFoldCrossValidation, ValidationPoolManager }
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.messages.external.{
  Algorithm,
  ExperimentQuery,
  MiningQuery,
  Validation => ApiValidation
}
import eu.hbp.mip.woken.messages.validation._
import eu.hbp.mip.woken.meta.{ MetaDataProtocol, VariableMetaData }
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

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job, result: Either[ErrorJobResult, PfaExperimentJobResult])

  def props(coordinatorConfig: CoordinatorConfig,
            algorithmLookup: String => Validation[AlgorithmDefinition]): Props =
    Props(new ExperimentActor(coordinatorConfig, algorithmLookup))

}

/** FSM States and internal data */
private[core] object ExperimentStates {
  import ExperimentActor.Job

  // FSM States

  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM state data

  sealed trait ExperimentData {
    def initiator: ActorRef
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  case object Uninitialized extends ExperimentData {
    def initiator = throw new IllegalAccessException()
  }

  case class PartialExperimentData(
      initiator: ActorRef,
      job: Job,
      results: Map[Algorithm, JobResult],
      algorithms: List[Algorithm]
  ) extends ExperimentData {
    def isComplete: Boolean = results.size == algorithms.length
  }

  case class CompletedExperimentData(
      initiator: ActorRef,
      job: Job,
      results: PfaExperimentJobResult
  ) extends ExperimentData

  object CompletedExperimentData {
    def apply(from: PartialExperimentData, jobsConfig: JobsConfiguration): CompletedExperimentData =
      CompletedExperimentData(
        initiator = from.initiator,
        job = from.job,
        results = PfaExperimentJobResult(experimentJobId = from.job.jobId,
                                         experimentNode = jobsConfig.node,
                                         results = from.results,
                                         algorithms = from.algorithms)
      )
  }
}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val coordinatorConfig: CoordinatorConfig,
                      algorithmLookup: String => Validation[AlgorithmDefinition])
    extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[ExperimentStates.State, ExperimentStates.ExperimentData] {

  import ExperimentActor._
  import ExperimentStates._

  log.info("Experiment started")

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(StartExperimentJob(job), _) if job.query.algorithms.nonEmpty =>
      val initiator   = sender()
      val algorithms  = job.query.algorithms
      val validations = job.query.validations

      log.info("Start new experiment job")
      log.info(s"List of algorithms: ${algorithms.mkString(",")}")

      // Spawn an AlgorithmActor for every algorithm
      val noResults = Map[Algorithm, JobResult]()
      val results: Map[Algorithm, JobResult] = algorithms.foldLeft(noResults) { (res, a) =>
        val jobId = UUID.randomUUID().toString
        val miningQuery = MiningQuery(
          variables = job.query.variables,
          covariables = job.query.covariables,
          grouping = job.query.grouping,
          filters = job.query.filters,
          algorithm = a
        )
        algorithmLookup(a.code).fold[Map[Algorithm, JobResult]](
          errorMessage => {
            res + (a -> ErrorJobResult(jobId,
                                       coordinatorConfig.jobsConf.node,
                                       OffsetDateTime.now(),
                                       a.code,
                                       errorMessage.reduceLeft(_ + ", " + _)))
          },
          algorithmDefinition => {
            val subJob = AlgorithmActor.Job(jobId,
                                            job.inputDb,
                                            job.inputTable,
                                            miningQuery,
                                            job.metadata,
                                            validations,
                                            algorithmDefinition)
            val worker = context.actorOf(
              AlgorithmActor.props(coordinatorConfig),
              AlgorithmActor.actorName(subJob)
            )
            worker ! AlgorithmActor.Start(subJob)
            res
          }
        )
      }

      if (results.size == algorithms.size) {
        val msg = "Experiment contains no algorithms or only invalid algorithms"
        initiator ! Response(job,
                             Left(
                               ErrorJobResult(job.jobId,
                                              coordinatorConfig.jobsConf.node,
                                              OffsetDateTime.now(),
                                              "experiment",
                                              msg)
                             ))
        stop(FSM.Failure(msg))
      } else {
        goto(WaitForWorkers) using PartialExperimentData(initiator, job, results, algorithms)
      }
  }

  when(WaitForWorkers) {
    case Event(AlgorithmActor.ResultResponse(algorithm, algorithmResults),
               previousExperimentData: PartialExperimentData) =>
      log.info(s"Received algorithm result $algorithmResults")
      val results        = previousExperimentData.results + (algorithm -> algorithmResults)
      val experimentData = previousExperimentData.copy(results = results)
      if (experimentData.isComplete) {
        log.info("All results received")
        goto(Reduce) using CompletedExperimentData(experimentData, coordinatorConfig.jobsConf)
      } else {
        log.info(
          s"Received ${experimentData.results.size} results out of ${experimentData.algorithms.size}"
        )
        stay using experimentData
      }

    case Event(AlgorithmActor.ErrorResponse(algorithm, error),
               previousExperimentData: PartialExperimentData) =>
      log.error(s"Algorithm ${algorithm.code} returned with error ${error.error.take(50)}")
      val results        = previousExperimentData.results + (algorithm -> error)
      val experimentData = previousExperimentData.copy(results = results)
      if (experimentData.isComplete) {
        log.info("All results received")
        goto(Reduce) using CompletedExperimentData(experimentData, coordinatorConfig.jobsConf)
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

      val results = experimentData.results

      coordinatorConfig.jobResultService.put(results)
      experimentData.initiator ! Response(experimentData.job, Right(results))

      log.info("Stopping...")
      stop
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning(s"Received unhandled request $e of type ${e.getClass} in state $stateName/$s")
      stay
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

  case class Job(jobId: String,
                 inputDb: String,
                 inputTable: String,
                 query: MiningQuery,
                 metadata: JsObject,
                 validations: List[ApiValidation],
                 algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)
  }

  // Incoming messages
  case class Start(job: Job)

  // Output messages
  case class ResultResponse(algorithm: Algorithm, model: JobResult)
  case class ErrorResponse(algorithm: Algorithm, error: ErrorJobResult)

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(new AlgorithmActor(coordinatorConfig))

  def actorName(job: Job): String =
    s"AlgorithmActor_job_${job.jobId}_algo_${job.query.algorithm.code}"

}

/** FSM States and internal data */
private[core] object AlgorithmStates {
  import AlgorithmActor.Job

  // Private messages
  case object Done

  // FSM States
  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM Data
  sealed trait AlgorithmData {
    def initiator: ActorRef
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  case object Uninitialized extends AlgorithmData {
    def initiator = throw new IllegalAccessException()
  }

  case class PartialAlgorithmData(initiator: ActorRef,
                                  job: Job,
                                  model: Option[JobResult],
                                  incomingValidations: Map[ApiValidation, Either[String, JsObject]],
                                  expectedValidationCount: Int)
      extends AlgorithmData {

    def isComplete: Boolean =
      (incomingValidations.size == expectedValidationCount) && model.isDefined
    def remainingValidations: Int = expectedValidationCount - incomingValidations.size
  }

  case class CompleteAlgorithmData(initiator: ActorRef,
                                   job: Job,
                                   model: JobResult,
                                   validations: Map[ApiValidation, Either[String, JsObject]])
      extends AlgorithmData

  object CompleteAlgorithmData {
    def apply(from: PartialAlgorithmData): CompleteAlgorithmData =
      new CompleteAlgorithmData(initiator = from.initiator,
                                job = from.job,
                                model = from.model.get,
                                validations = from.incomingValidations)
  }
}

class AlgorithmActor(coordinatorConfig: CoordinatorConfig)
    extends Actor
    with ActorLogging
    with LoggingFSM[AlgorithmStates.State, AlgorithmStates.AlgorithmData] {

  import AlgorithmActor._
  import AlgorithmStates._

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(Start(job), _) =>
      val initiator   = sender()
      val algorithm   = job.query.algorithm
      val validations = if (job.algorithmDefinition.predictive) job.validations else Nil

      log.info(s"Start job for algorithm ${algorithm.code}")
      log.info(s"List of validations: ${validations.size}")

      // Spawn a CoordinatorActor
      {
        val jobId = UUID.randomUUID().toString
        val subJob =
          DockerJob(jobId,
                    job.algorithmDefinition.dockerImage,
                    job.inputDb,
                    job.inputTable,
                    job.query,
                    job.metadata)
        val worker = context.actorOf(
          CoordinatorActor.props(coordinatorConfig),
          CoordinatorActor.actorName(subJob)
        )
        worker ! StartCoordinatorJob(subJob)
      }

      // Spawn a CrossValidationActor for every validation
      for (v <- validations) {
        val jobId = UUID.randomUUID().toString
        val subJob =
          CrossValidationActor.Job(jobId,
                                   job.inputDb,
                                   job.inputTable,
                                   job.query,
                                   job.metadata,
                                   v,
                                   job.algorithmDefinition)
        val validationWorker = context.actorOf(
          CrossValidationActor.props(coordinatorConfig),
          CrossValidationActor.actorName(subJob)
        )
        validationWorker ! CrossValidationActor.Start(subJob)
      }

      goto(WaitForWorkers) using PartialAlgorithmData(initiator, job, None, Map(), validations.size)
  }

  when(WaitForWorkers) {
    case Event(CoordinatorActor.Response(_, List(model: ErrorJobResult)),
               previousData: PartialAlgorithmData) =>
      log.error(
        s"Execution of algorithm ${algorithmIn(previousData)} failed with message: ${model.error
          .take(50)}"
      )
      // We cannot train the model, we notify the initiator and we stop
      val initiator = previousData.initiator
      initiator ! ErrorResponse(previousData.job.query.algorithm, model)

      log.info("Stopping...")
      stop

    case Event(CoordinatorActor.Response(_, List(model: JobResult)),
               previousData: PartialAlgorithmData) =>
      val data = previousData.copy(model = Some(model))
      if (data.isComplete) {
        log.info(
          s"Received job result, processing of algorithm ${algorithmIn(previousData)} complete"
        )
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        log.info(
          s"Received job result for algorithm algorithm ${algorithmIn(previousData)}, pending ${data.remainingValidations} validations"
        )
        stay using data
      }

    case Event(CrossValidationActor.ResultResponse(validation, results),
               previousData: PartialAlgorithmData) =>
      val data = previousData.copy(
        incomingValidations = previousData.incomingValidations + (validation -> Right(results))
      )
      if (data.isComplete) {
        log.info(
          s"Received validation result, processing of algorithm ${algorithmIn(previousData)} complete"
        )
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        log.info(
          s"Received validation result for algorithm algorithm ${algorithmIn(previousData)}, pending ${data.remainingValidations} validations"
        )
        if (data.model.isEmpty)
          log.info("Waiting for missing PFA model...")
        stay using data
      }

    case Event(CrossValidationActor.ErrorResponse(validation, message),
               previousData: PartialAlgorithmData) =>
      log.error(
        s"Validation of algorithm ${algorithmIn(previousData)} returned with error : $message"
      )
      val data = previousData.copy(
        incomingValidations = previousData.incomingValidations + (validation -> Left(message))
      )
      if (data.isComplete) {
        log.info(
          s"Received validation error, processing of algorithm ${algorithmIn(previousData)} complete"
        )
        goto(Reduce) using CompleteAlgorithmData(data)
      } else {
        log.info(
          s"Received validation result for algorithm algorithm ${algorithmIn(previousData)}, pending ${data.remainingValidations} validations"
        )
        if (data.model.isEmpty)
          log.info("Waiting for missing PFA model...")
        stay using data
      }
  }

  when(Reduce) {
    case Event(Done, data: CompleteAlgorithmData) =>
      val validations = JsArray(
        data.validations
          .map({
            case (key, Right(value)) =>
              JsObject("code" -> JsString(key.code), "name" -> JsString(key.name), "data" -> value)
            case (key, Left(message)) =>
              JsObject("code"  -> JsString(key.code),
                       "name"  -> JsString(key.name),
                       "error" -> JsString(message))
          })
          .toVector
      )

      val model = data.model match {
        case pfa: PfaJobResult => pfa.injectCell("validations", validations)
        case m                 => m
      }

      data.initiator ! ResultResponse(data.job.query.algorithm, model)
      log.info("Stopping...")
      stop
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning(s"Received unhandled request $e of type ${e.getClass} in state $stateName/$s")
      stay
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()

  private def algorithmIn(data: PartialAlgorithmData) =
    data.job.query.algorithm.code

}

/**
  * We use the companion object to hold all the messages that the ``ValidationActor``
  * receives.
  */
object CrossValidationActor {

  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: MiningQuery,
      metadata: JsObject,
      validation: ApiValidation,
      algorithmDefinition: AlgorithmDefinition
  )

  // Incoming messages
  case class Start(job: Job)

  // Output Messages
  case class ResultResponse(validation: ApiValidation, data: JsObject)
  case class ErrorResponse(validation: ApiValidation, message: String)

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(new CrossValidationActor(coordinatorConfig))

  def actorName(job: Job): String =
    s"CrossValidationActor_job_${job.jobId}_algo_${job.query.algorithm.code}"

}

/** FSM States and internal data */
private[core] object CrossValidationStates {
  import CrossValidationActor.Job

  // Private messages
  case object Done

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

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class WaitForWorkersState(initiator: ActorRef,
                                 job: Job,
                                 validation: KFoldCrossValidation,
                                 workers: Map[ActorRef, Fold],
                                 foldCount: Int,
                                 targetMetaData: VariableMetaData,
                                 average: (List[String], List[String]),
                                 results: Map[String, ScoringResult])
      extends StateData

  case class ReduceData(initiator: ActorRef,
                        job: Job,
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
      val algorithm  = job.query.algorithm
      val validation = job.validation

      log.info(s"List of folds: ${validation.parameters("k")}")

      val foldCount = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val crossValidation = KFoldCrossValidation(job, foldCount, coordinatorConfig.featuresDatabase)

      assert(crossValidation.partition.size == foldCount)

      // For every fold
      val workers = crossValidation.partition.map {
        case (fold, (s, n)) =>
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

          val worker = context.actorOf(
            CoordinatorActor.props(coordinatorConfig)
          )
          //workers(worker) = fold

          worker ! StartCoordinatorJob(subJob)

          (worker, fold)
      }

      // TODO: move this code in a better place, test it
      import eu.hbp.mip.woken.core.model.Queries._
      import MetaDataProtocol._
      val targetMetaData: VariableMetaData = job.metadata
        .convertTo[Map[String, VariableMetaData]]
        .get(job.query.dbVariables.head) match {
        case Some(v: VariableMetaData) => v
        case None                      => throw new Exception("Problem with variables' meta data!")
      }

      val initiator = sender()
      goto(WaitForWorkers) using WaitForWorkersState(
        initiator = initiator,
        job = job,
        validation = crossValidation,
        workers = workers,
        targetMetaData = targetMetaData,
        average = (Nil, Nil),
        results = Map(),
        foldCount = foldCount
      )
  }

  when(WaitForWorkers) {
    case Event(CoordinatorActor.Response(_, List(pfa: PfaJobResult)), data: WaitForWorkersState) =>
      // Validate the results
      log.info("Received result from local method.")
      val model    = pfa.model.toString()
      val fold     = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      val sendTo = nextValidationActor
      log.info(s"Send a validation work for fold $fold to pool agent: $sendTo")
      sendTo.fold {
        data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation,
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
            data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation,
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
              goto(Reduce) using ReduceData(initiator = data.initiator,
                                            job = data.job,
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
          data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
        case (None, Some(_)) =>
          val message = s"Empty test set on fold $fold"
          log.error(message)
          data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
        case _ =>
          val message = s"No data selected during fold $fold"
          log.error(message)
          data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          log.info("Stopping...")
          stop
      }

    case Event(ValidationError(message), data: WaitForWorkersState) =>
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      log.info("Stopping...")
      stop

    case Event(CoordinatorActor.Response(_, List(error: ErrorJobResult)),
               data: WaitForWorkersState) =>
      val message = error.error
      log.error(s"Error on cross validation job ${error.jobId}: $message")
      // On training fold fails, we notify supervisor and we stop
      data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
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
            data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                                "Validation system not connected")
          ) { future =>
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            // Aggregation of results from all folds
            val jsonValidation = JsObject(
              "type"    -> JsString("KFoldCrossValidation"),
              "average" -> scores.scores,
              "folds"   -> new JsObject(data.results.mapValues(s => s.scores))
            )

            data.initiator ! CrossValidationActor.ResultResponse(data.job.validation,
                                                                 jsonValidation)
          }
        case _ =>
          val message = s"Final reduce for cross-validation uses empty datasets"
          log.error(message)
          data.initiator ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      }
      log.info("Stopping...")
      stop
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning(s"Received unhandled request $e of type ${e.getClass} in state $stateName/$s")
      stay
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}
