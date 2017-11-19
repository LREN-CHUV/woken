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
import eu.hbp.mip.woken.config.WokenConfig.defaultSettings.{ defaultDb, dockerImage, isPredictive }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.core.validation.{ KFoldCrossValidation, ValidationPoolManager }
import eu.hbp.mip.woken.dao.JobResultsDAL
import eu.hbp.mip.woken.messages.external.{ Algorithm, Validation => ApiValidation }
import eu.hbp.mip.woken.messages.validation._
import eu.hbp.mip.woken.meta.{VariableMetaData, MetaDataProtocol}
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{ JsString, _ }

import scala.concurrent.{ Await, Future }
import scala.util.Random

/**
  * We use the companion object to hold all the messages that the ``ExperimentCoordinatorActor``
  * receives.
  */
object ExperimentActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: Option[String],
      algorithms: Seq[Algorithm],
      validations: Seq[ApiValidation],
      parameters: Map[String, String]
  )

  case class Start(job: Job) extends RestMessage {
    import ApiJsonSupport._
    import spray.httpx.SprayJsonSupport._
    implicit val jobFormat: RootJsonFormat[Job] = jsonFormat5(Job.apply)
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

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

  // FSM Data
  case class Data(
      job: Job,
      replyTo: ActorRef,
      results: collection.mutable.Map[Algorithm, String],
      algorithms: Seq[Algorithm]
  )

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val chronosService: ActorRef,
                      val resultDatabase: JobResultsDAL,
                      val federationDatabase: Option[JobResultsDAL],
                      val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[ExperimentStates.State, Option[ExperimentStates.Data]] {

  import ExperimentActor._
  import ExperimentStates._

  def reduceAndStop(data: Data): State = {

    //TODO WP3 Save the results in results DB

    // Concatenate results while respecting received algorithms order
    val output = JsArray(
      data.algorithms
        .map(
          a =>
            JsObject("code" -> JsString(a.code),
                     "name" -> JsString(a.name),
                     "data" -> JsonParser(data.results(a)))
        )
        .toVector
    )

    data.replyTo ! jobResultsFactory(
      Seq(
        JobResult(jobId = data.job.jobId,
                  node = "",
                  timestamp = OffsetDateTime.now(),
                  shape = "pfa_json",
                  function = "",
                  data = Some(output.compactPrint),
                  error = None)
      )
    )
    stop
  }

  startWith(WaitForNewJob, None)

  when(WaitForNewJob) {
    case Event(Start(job), _) => {
      val replyTo = sender()

      val algorithms  = job.algorithms
      val validations = job.validations

      log.warning(s"List of algorithms: ${algorithms.mkString(",")}")

      if (algorithms.nonEmpty) {

        // Spawn an AlgorithmActor for every algorithm
        for (a <- algorithms) {
          val jobId  = UUID.randomUUID().toString
          val subjob = AlgorithmActor.Job(jobId, Some(defaultDb), a, validations, job.parameters)
          val worker = context.actorOf(
            Props(classOf[AlgorithmActor],
                  chronosService,
                  resultDatabase,
                  federationDatabase,
                  RequestProtocol)
          )
          worker ! AlgorithmActor.Start(subjob)
        }

        goto(WaitForWorkers) using Some(Data(job, replyTo, collection.mutable.Map(), algorithms))
      } else {
        stay
      }
    }
  }

  when(WaitForWorkers) {
    case Event(AlgorithmActor.ResultResponse(algorithm, results), Some(data: Data)) => {
      data.results(algorithm) = results
      if (data.results.size == data.algorithms.length) reduceAndStop(data) else stay
    }
    case Event(AlgorithmActor.ErrorResponse(algorithm, message), Some(data: Data)) => {
      log.error(message)
      data.results(algorithm) = message
      if (data.results.size == data.algorithms.length) reduceAndStop(data) else stay
    }
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
      inputDb: Option[String],
      algorithm: Algorithm,
      validations: Seq[ApiValidation],
      parameters: Map[String, String]
  )
  case class Start(job: Job)

  case class ResultResponse(algorithm: Algorithm, data: String)
  case class ErrorResponse(algorithm: Algorithm, message: String)

  // TODO not sure if useful
  /*implicit val resultFormat = jsonFormat2(ResultResponse.apply)
  implicit val errorResponseFormat = jsonFormat2(ErrorResponse.apply)*/
}

/** FSM States and internal data */
object AlgorithmStates {
  import AlgorithmActor.Job

  // FSM States
  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State

  // FSM Data
  case class Data(job: Job,
                  replyTo: ActorRef,
                  var model: Option[String],
                  results: collection.mutable.Map[ApiValidation, String],
                  validationCount: Int)

}

class AlgorithmActor(val chronosService: ActorRef,
                     val resultDatabase: JobResultsDAL,
                     val federationDatabase: Option[JobResultsDAL],
                     val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with LoggingFSM[AlgorithmStates.State, Option[AlgorithmStates.Data]] {

  import AlgorithmActor._
  import AlgorithmStates._

  def reduceAndStop(data: AlgorithmStates.Data): State = {

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
    val pfa = data.model.get
      .replaceFirst("\"cells\":\\{", "\"cells\":{\"validations\":" + validations.compactPrint + ",")

    data.replyTo ! AlgorithmActor.ResultResponse(data.job.algorithm, pfa)
    stop
  }

  startWith(WaitForNewJob, None)

  when(WaitForNewJob) {
    case Event(AlgorithmActor.Start(job), _) => {
      val replyTo = sender()

      val algorithm   = job.algorithm
      val validations = if (isPredictive(algorithm.code)) job.validations else List()

      val parameters = job.parameters ++ FunctionsInOut.algoParameters(algorithm)

      log.warning(s"List of validations: ${validations.size}")

      // Spawn a LocalCoordinatorActor
      val jobId = UUID.randomUUID().toString
      val subjob =
        JobDto(jobId, dockerImage(algorithm.code), None, None, Some(defaultDb), parameters, None)
      val worker = context.actorOf(
        CoordinatorActor.props(chronosService, resultDatabase, None, jobResultsFactory)
      )
      worker ! CoordinatorActor.Start(subjob)

      // Spawn a CrossValidationActor for every validation
      for (v <- validations) {
        val jobId  = UUID.randomUUID().toString
        val subjob = CrossValidationActor.Job(jobId, job.inputDb, algorithm, v, parameters)
        val validationWorker = context.actorOf(
          Props(classOf[CrossValidationActor],
                chronosService,
                resultDatabase,
                federationDatabase,
                jobResultsFactory)
        )
        validationWorker ! CrossValidationActor.Start(subjob)
      }

      goto(WaitForWorkers) using Some(
        Data(job, replyTo, None, collection.mutable.Map(), validations.size)
      )
    }
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), Some(data: Data)) => {
      data.model = Some(pfa.compactPrint)
      if (data.results.size == data.validationCount) reduceAndStop(data) else stay
    }
    case Event(CoordinatorActor.ErrorResponse(message), Some(data: Data)) => {
      log.error(message)
      // We cannot trained the model we notify supervisor and we stop
      context.parent ! ErrorResponse(data.job.algorithm, message)
      stop
    }
    case Event(CrossValidationActor.ResultResponse(validation, results), Some(data: Data)) => {
      data.results(validation) = results
      if ((data.results.size == data.validationCount) && data.model.isDefined) reduceAndStop(data)
      else stay
    }
    case Event(CrossValidationActor.ErrorResponse(validation, message), Some(data: Data)) => {
      log.error(message)
      data.results(validation) = message
      if ((data.results.size == data.validationCount) && data.model.isDefined) reduceAndStop(data)
      else stay
    }
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
      inputDb: Option[String],
      algorithm: Algorithm,
      validation: ApiValidation,
      parameters: Map[String, String]
  )
  case class Start(job: Job)

  // Output Messages
  case class ResultResponse(validation: ApiValidation, data: String)
  case class ErrorResponse(validation: ApiValidation, message: String)

  // TODO not sure if useful
  /*implicit val resultFormat = jsonFormat2(ResultResponse.apply)
  implicit val errorResponseFormat = jsonFormat2(ErrorResponse.apply)*/
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
    def job: JobDto
  }

  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class WaitForWorkersState(job: Job,
                                 replyTo: ActorRef,
                                 validation: KFoldCrossValidation,
                                 workers: Map[ActorRef, Fold],
                                 targetMetaData: VariableMetaData,
                                 average: (List[String], List[String]),
                                 results: Map[String, ScoringResult],
                                 foldsCount: Int)
      extends StateData

  case class ReduceData(job: Job,
                        replyTo: ActorRef,
                        targetMetaData: VariableMetaData,
                        average: (List[String], List[String]),
                        results: Map[String, ScoringResult])
      extends StateData

}

/**
  *
  * @param chronosService
  * @param resultDatabase
  * @param federationDatabase
  * @param jobResultsFactory
  */
class CrossValidationActor(val chronosService: ActorRef,
                           val resultDatabase: JobResultsDAL,
                           val federationDatabase: Option[JobResultsDAL],
                           val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with LoggingFSM[CrossValidationStates.State, CrossValidationStates.StateData] {

  import CrossValidationActor._
  import CrossValidationStates._

  def adjust[A, B](m: Map[A, B], k: A)(f: B => B): Map[A, B] = m.updated(k, f(m(k)))

  // TODO - LC: use ReduceState
  def reduceAndStop(data: ReduceData): State = {

    import cats.syntax.list._
    import scala.concurrent.duration._
    import language.postfixOps

    (data.average._1.toNel, data.average._2.toNel) match {
      case (Some(r), Some(gt)) =>
        implicit val timeout: util.Timeout = Timeout(5 minutes)

        // TODO: targetMetaData is null!!

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
    stop
  }

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
      val algorithm  = job.algorithm
      val validation = job.validation

      log.info(s"List of folds: ${validation.parameters("k")}")

      val k = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val xvalidation = KFoldCrossValidation(job, k)
      //val workers: collection.mutable.Map[ActorRef, String] = collection.mutable.Map()

      // For every fold
      val workers = xvalidation.partition.map {
        case (fold, (s, n)) => {
          // Spawn a LocalCoordinatorActor for that one particular fold
          val jobId = UUID.randomUUID().toString
          // TODO To be removed in WP3
          val parameters = adjust(job.parameters, "PARAM_query")(
            (x: String) => x + " EXCEPT ALL (" + x + s" OFFSET $s LIMIT $n)"
          )
          val subJob = JobDto(jobId = jobId,
                              dockerImage = dockerImage(algorithm.code),
                              federationDockerImage = None,
                              jobName = None,
                              inputDb = Some(defaultDb),
                              parameters = parameters,
                              nodes = None)
          val worker = context.actorOf(
            CoordinatorActor
              .props(chronosService, resultDatabase, federationDatabase, jobResultsFactory)
          )
          //workers(worker) = fold
          worker ! CoordinatorActor.Start(subJob)

          (worker, fold)
        }
      }

      import MetaDataProtocol._
      val targetMetaData: VariableMetaData = job
        .parameters("PARAM_meta")
        .parseJson
        .convertTo[Map[String, VariableMetaData]]
        .get(job.parameters("PARAM_variables").split(",").head) match {
        case Some(v: VariableMetaData) => v
        case None                      => throw new Exception("Problem with variables' meta data!")
      }

      goto(WaitForWorkers) using WaitForWorkersState(job,
                                                     replyTo,
                                                     xvalidation,
                                                     workers,
                                                     targetMetaData,
                                                     (Nil, Nil),
                                                     Map(),
                                                     k)
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), data: WaitForWorkersState) =>
      // Validate the results
      log.info("Received result from local method.")
      val model    = pfa.toString()
      val fold     = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      val sendTo = nextValidationActor
      log.info("Send a validation work for fold " + fold + " to pool agent: " + sendTo)
      sendTo.fold {
        context.parent ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                            "Validation system not available")
        stop
      } { validationActor =>
        validationActor ! ValidationQuery(fold, model, testData, data.targetMetaData)
        stay
      }

    case Event(ValidationResult(fold, targetMetaData, results), data: WaitForWorkersState) =>
      log.info("Received validation results for fold " + fold + ".")
      // Score the results
      val groundTruth = data.validation
        .getTestSet(fold)
        ._2
        .map(x => x.asJsObject.fields.toList.head._2.compactPrint)

      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (results.toNel, groundTruth.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout: util.Timeout = Timeout(5 minutes)
          val futureO: Option[Future[_]] =
            nextValidationActor.map(_ ? ScoringQuery(r, gt, data.targetMetaData))

          futureO.fold {
            data.replyTo ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                              "Validation system not connected")
            stop
          } { future =>
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            data.results(fold) = scores

            // TODO To be improved with new Spark integration
            // Update the average score
            val updatedAverage = (data.average._1 ::: results, data.average._2 ::: groundTruth)

            // TODO - LC: use updatedAverage in the next step
            // If we have validated all the fold we finish!
            if (data.results.size == data.foldsCount) reduceAndStop(data) else stay
          }

        case (Some(r), None) =>
          val message = s"No results on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
        case (None, Some(gt)) =>
          val message = s"Empty test set on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
        case (None, None) =>
          val message = s"No data selected during fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
      }

    case Event(ValidationError(message), data: WaitForWorkersState) =>
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop

    case Event(Error(message), data: WaitForWorkersState) =>
      log.error(message)
      // On training fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
  }

  initialize()
}
