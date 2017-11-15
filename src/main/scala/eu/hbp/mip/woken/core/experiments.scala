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
import akka.util.Timeout
import eu.hbp.mip.woken.api._
import eu.hbp.mip.woken.config.WokenConfig.defaultSettings.{ defaultDb, dockerImage, isPredictive }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.core.validation.{ KFoldCrossValidation, ValidationPoolManager }
import eu.hbp.mip.woken.dao.JobResultsDAL
import eu.hbp.mip.woken.messages.external.{ Algorithm, Validation => ApiValidation }
import eu.hbp.mip.woken.messages.validation._
import eu.hbp.mip.woken.meta.VariableMetaData
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{ JsString, _ }
import cats.data.NonEmptyList

import scala.concurrent.duration.Duration
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
        JobResult(data.job.jobId,
                  "",
                  OffsetDateTime.now(),
                  Some(output.compactPrint),
                  None,
                  "pfa_json",
                  "")
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

  // FSM Data
  case class CrossValidationData(job: Job,
                                 replyTo: ActorRef,
                                 var validation: KFoldCrossValidation,
                                 workers: Map[ActorRef, String],
                                 var targetMetaData: VariableMetaData,
                                 var average: (List[String], List[String]),
                                 var results: collection.mutable.Map[String, ScoringResult],
                                 foldsCount: Int)
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
    with LoggingFSM[CrossValidationStates.State, Option[CrossValidationStates.CrossValidationData]] {

  import CrossValidationActor._
  import CrossValidationStates._

  def adjust[A, B](m: Map[A, B], k: A)(f: B => B): Map[A, B] = m.updated(k, f(m(k)))

  def reduceAndStop(data: CrossValidationData): State = {

    import cats.syntax.list._
    import scala.concurrent.duration._
    import language.postfixOps

    (data.average._1.toNel, data.average._2.toNel) match {
      case (Some(r), Some(gt)) =>
        implicit val timeout = Timeout(5 minutes)
        val sendTo           = nextValidationActor
        val scores           = nextValidationActor ? ScoringQuery(r, gt, data.targetMetaData)

        // Aggregation of results from all folds
        val jsonValidation = JsObject(
          "type"    -> JsString("KFoldCrossValidation"),
          "average" -> scores.asInstanceOf[ScoringResult].scores,
          "folds"   -> new JsObject(data.results.mapValues(s => s.scores).toMap)
        )

        data.replyTo ! CrossValidationActor.ResultResponse(data.job.validation,
                                                           jsonValidation.compactPrint)
      case _ =>
        val message = s"Final reduce for cross-validation uses empty datasets"
        log.error(message)
        context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
    }
    stop
  }

  def nextValidationActor: ActorSelection = {
    val validationPool = ValidationPoolManager.validationPool
    //TODO If validationPool.size == 0, we cannot perform the cross validation we should throw an error!
    context.actorSelection(validationPool.toList(Random.nextInt(validationPool.size)))
  }

  startWith(WaitForNewJob, None)

  when(WaitForNewJob) {
    case Event(Start(job), _) => {
      val replyTo = sender()

      val algorithm  = job.algorithm
      val validation = job.validation

      log.warning(s"List of folds: ${validation.parameters("k")}")

      val k = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val xvalidation                                       = KFoldCrossValidation(job, k)
      val workers: collection.mutable.Map[ActorRef, String] = collection.mutable.Map()

      // For every fold
      xvalidation.partition.foreach({
        case (fold, (s, n)) => {
          // Spawn a LocalCoordinatorActor for that one particular fold
          val jobId = UUID.randomUUID().toString
          // TODO To be removed in WP3
          val parameters = adjust(job.parameters, "PARAM_query")(
            (x: String) => x + " EXCEPT ALL (" + x + s" OFFSET $s LIMIT $n)"
          )
          val subjob = JobDto(jobId,
                              dockerImage(algorithm.code),
                              None,
                              None,
                              Some(defaultDb),
                              parameters,
                              None)
          val worker = context.actorOf(
            CoordinatorActor
              .props(chronosService, resultDatabase, federationDatabase, jobResultsFactory)
          )
          workers(worker) = fold
          worker ! CoordinatorActor.Start(subjob)
        }
      })
      goto(WaitForWorkers) using Some(
        CrossValidationData(job,
                            replyTo,
                            xvalidation,
                            workers.toMap,
                            null,
                            (Nil, Nil),
                            collection.mutable.Map(),
                            k)
      )
    }
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), Some(data: CrossValidationData)) => {
      // Validate the results
      log.info("Received result from local method.")
      val model    = pfa.toString()
      val fold     = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      // TODO move all this deserialization code
      // Get target variable's meta data
      object MyJsonProtocol extends DefaultJsonProtocol {
        //implicit val variableMetaData = jsonFormat6(VariableMetaData)

        implicit object VariableMetaDataFormat extends RootJsonFormat[VariableMetaData] {
          // Some fields are optional so we produce a list of options and
          // then flatten it to only write the fields that were Some(..)
          def write(item: VariableMetaData): JsObject =
            JsObject(
              ((item.methodology match {
                case Some(m) => Some("methodology" -> m.toJson)
                case _       => None
              }) :: (item.units match {
                case Some(u) => Some("units" -> u.toJson)
                case _       => None
              }) :: (item.enumerations match {
                case Some(e) => Some("enumerations" -> e.map({ case (c, l) => (c, l) }).toJson)
                case _       => None
              }) :: List(
                Some("code"  -> item.code.toJson),
                Some("label" -> item.label.toJson),
                Some("type"  -> item.`type`.toJson)
              )).flatten: _*
            )

          // We use the "standard" getFields method to extract the mandatory fields.
          // For optional fields we extract them directly from the fields map using get,
          // which already handles the option wrapping for us so all we have to do is map the option
          def read(json: JsValue): VariableMetaData = {
            val jsObject = json.asJsObject

            jsObject.getFields("code", "label", "type") match {
              case Seq(code, label, t) ⇒
                VariableMetaData(
                  code.convertTo[String],
                  label.convertTo[String],
                  t.convertTo[String],
                  jsObject.fields.get("methodology").map(_.convertTo[String]),
                  jsObject.fields.get("units").map(_.convertTo[String]),
                  jsObject.fields
                    .get("enumerations")
                    .map(
                      _.convertTo[JsArray].elements
                        .map(
                          o =>
                            o.asJsObject
                              .fields("code")
                              .convertTo[String] -> o.asJsObject
                              .fields("label")
                              .convertTo[String]
                        )
                        .toMap
                    )
                )
              case other ⇒
                deserializationError(
                  "Cannot deserialize VariableMetaData: invalid input. Raw input: " + other
                )
            }
          }
        }
      }
      import MyJsonProtocol._
      val targetMetaData: VariableMetaData = data.job
        .parameters("PARAM_meta")
        .parseJson
        .convertTo[Map[String, VariableMetaData]]
        .get(data.job.parameters("PARAM_variables").split(",").head) match {
        case Some(v: VariableMetaData) => v
        case None                      => throw new Exception("Problem with variables' meta data!")
      }

      val sendTo = nextValidationActor
      log.info("Send a validation work for fold " + fold + " to pool agent: " + sendTo)
      sendTo ! ValidationQuery(fold, model, testData, targetMetaData)
      stay
    }
    case Event(ValidationResult(fold, targetMetaData, results), Some(data: CrossValidationData)) => {
      log.info("Received validation results for fold " + fold + ".")
      // Score the results
      val groundTruth = data.validation
        .getTestSet(fold)
        ._2
        .map(x => x.asJsObject.fields.toList.head._2.compactPrint)

      import cats.syntax.list._
      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (results.toNel, groundTruth.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout = Timeout(5 minutes)
          val sendTo           = nextValidationActor
          val scores           = nextValidationActor ? ScoringQuery(r, gt, targetMetaData)
          data.results(fold) = scores.asInstanceOf[ScoringResult]

          // TODO To be improved with new Spark integration
          // Update the average score
          data.targetMetaData = targetMetaData
          data.average = (data.average._1 ::: results, data.average._2 ::: groundTruth)

          // If we have validated all the fold we finish!
          if (data.results.size == data.foldsCount) reduceAndStop(data) else stay

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

    }
    case Event(ValidationError(message), Some(data: CrossValidationData)) => {
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
    }
    case Event(Error(message), Some(data: CrossValidationData)) => {
      log.error(message)
      // On training fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
    }
  }

  initialize()
}
