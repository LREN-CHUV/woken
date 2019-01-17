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

package ch.chuv.lren.woken.mining

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, ZipWith}
import cats.Monoid
import cats.data.NonEmptyList
import cats.data.Validated._
import cats.effect.{Effect, Resource}
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.{AlgorithmExecutor, AlgorithmResults}
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.fp.runNow
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{DispatcherService, FeaturesService, FeaturesTableService, JobResultService}
import ch.chuv.lren.woken.validation.flows.AlgorithmWithCVFlow
import ch.chuv.lren.woken.validation.{FeaturesSplitter, FeaturesSplitterDefinition, defineSplitters}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * We use the companion object to hold all the messages that the ``ExperimentActor`` receives.
  */
object ExperimentActor {

  // Incoming messages
  type Job = ExperimentJob

  /**
    * Start a new experiment job.
    *
    * @param job - experiment job
    * @param replyTo Actor to reply to. Can be Actor.noSender when the ask pattern is used. This information is added in preparation for Akka Typed
    * @param initiator The initiator of the request, this information will be returned by CoordinatorActor.Response#initiator.
    *                  It can also have the value Actor.noSender
    */
  case class StartExperimentJob(job: ExperimentJob, replyTo: ActorRef, initiator: ActorRef)

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job,
                      result: Either[ErrorJobResult, ExperimentJobResult],
                      initiator: ActorRef)

  def props[F[_]: Effect](algorithmExecutor: AlgorithmExecutor[F],
                          dispatcherService: DispatcherService,
                          featuresService: FeaturesService[F],
                          jobResultService: JobResultService[F],
                          wokenWorker: WokenWorker[F]): Props =
    Props(
      new ExperimentActor(algorithmExecutor,
                          dispatcherService,
                          featuresService,
                          jobResultService,
                          wokenWorker)
    )

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor[F[_]: Effect](val algorithmExecutor: AlgorithmExecutor[F],
                                    val dispatcherService: DispatcherService,
                                    val featuresService: FeaturesService[F],
                                    val jobResultService: JobResultService[F],
                                    val wokenWorker: WokenWorker[F])
    extends Actor
    with LazyLogging {

  import ExperimentActor._

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error("Runtime error detected", err)
      Supervision.Resume
    case err =>
      logger.error("Unknown error. Stopping the stream.", err)
      Supervision.Stop
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(context.system).withSupervisionStrategy(decider)
  )

  implicit val ec: ExecutionContext = context.dispatcher

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.NonUnitStatements"))
  override def receive: Receive = {

    case StartExperimentJob(job, requestedReplyTo, initiator) if job.query.algorithms.isEmpty =>
      val replyTo = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo
      val msg     = "Experiment contains no algorithms"
      completeWithError(job, msg, initiator, replyTo)

    case StartExperimentJob(job, requestedReplyTo, initiator) if job.query.algorithms.nonEmpty =>
      val replyTo                         = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo
      val thisActor                       = self
      val algorithms: List[AlgorithmSpec] = job.query.algorithms
      val featuresTableServiceV =
        featuresService.featuresTable(job.inputTable)

      val containsPredictiveAlgorithms = job.queryAlgorithms.exists {
        case (_, defn) => defn.predictive
      }

      val splitterDefsV: Validation[List[FeaturesSplitterDefinition]] =
        if (containsPredictiveAlgorithms) {
          defineSplitters(job.query.validations)
        } else Nil.validNel[String]

      logger.info(
        s"Start new experiment job $job ${if (containsPredictiveAlgorithms) "with" else "without"} predictive algorithms"
      )
      logger.info(s"List of algorithms: ${algorithms.mkString(",")}")

      ((featuresTableServiceV, splitterDefsV) mapN Tuple2.apply).fold(
        err => {
          val msg = s"""Invalid definition of validations: ${err.mkString_("", ",", "")}"""
          completeWithError(job, msg, initiator, replyTo)
        }, {
          case (featuresTableService, splitterDefs) =>
            if (splitterDefs.isEmpty) {
              val future = executeExperimentFlow(job, Nil, featuresTableService, initiator)
              completeExperiment(job, future, thisActor, initiator, replyTo)
            } else
              executeExtendedExperimentFlow(job,
                                            splitterDefs,
                                            featuresTableService,
                                            thisActor,
                                            initiator,
                                            replyTo)
        }
      )

    case e =>
      logger.error(s"Unhandled message: $e")
      context stop self
  }

  private def executeExtendedExperimentFlow(
      job: ExperimentJob,
      splitterDefs: List[FeaturesSplitterDefinition],
      featuresTableService: FeaturesTableService[F],
      thisActor: ActorRef,
      initiator: ActorRef,
      replyTo: ActorRef
  ): Unit =
    featuresTableService
      .createExtendedFeaturesTable(job.query.filters,
                                   Nil,
                                   splitterDefs.map(_.splitColumn),
                                   splitterDefs)
      .fold(
        err => {
          val msg =
            s"""Invalid definition of extended features table: ${err
              .mkString_("", ",", "")}"""
          completeWithError(job, msg, initiator, replyTo)
        },
        (extendedFeaturesTableR: Resource[F, FeaturesTableService[F]]) => {
          val task = extendedFeaturesTableR.use[Unit] {
            extendedFeaturesTable =>
              val splitters = splitterDefs.map {
                FeaturesSplitter(_, extendedFeaturesTable)
              }

              val extTable = extendedFeaturesTable.table.table
              val extendedJob = job.copy(
                inputTable = extTable,
                query = job.query.copy(targetTable = Some(extTable.name))
              )

              validateFolds(extendedJob, splitterDefs, extendedFeaturesTable)
                .flatMap {
                  case Left(err) =>
                    val msg =
                      s"""Invalid folding of extended features table: ${err
                        .mkString_("", ",", "")}"""
                    Effect[F].delay(completeWithError(job, msg, initiator, replyTo))
                  case Right(_) =>
                    Effect[F].defer {
                      val experimentFlowF =
                        executeExperimentFlow(extendedJob,
                                              splitters,
                                              extendedFeaturesTable,
                                              initiator)
                      completeExperiment(job, experimentFlowF, thisActor, initiator, replyTo)

                      // Wait for the experiment flow to complete
                      Effect[F].async { cb =>
                        experimentFlowF.onComplete {
                          case Success(_)     => cb(Right(()))
                          case Failure(error) => cb(Left(error))
                        }
                      }
                    }
                }

          }
          Effect[F]
            .toIO(task)
            .attempt
            .unsafeRunSync()
            .fold(
              err => completeWithError(job, err.toString, initiator, replyTo),
              _ => ()
            )
        }
      )

  private def validateFolds(
      job: Job,
      splitterDefs: List[FeaturesSplitterDefinition],
      extendedFeaturesTable: FeaturesTableService[F]
  ): F[Either[NonEmptyList[String], Unit]] = {
    implicit val FPlus: Monoid[Either[NonEmptyList[String], Unit]] =
      new Monoid[Either[NonEmptyList[String], Unit]] {
        def empty: Either[NonEmptyList[String], Unit] = ().rightNel[String]
        def combine(
            x: Either[NonEmptyList[String], Unit],
            y: Either[NonEmptyList[String], Unit]
        ): Either[NonEmptyList[String], Unit] =
          x.combine(y)
      }

    splitterDefs
      .map { splitterDef =>
        extendedFeaturesTable
          .countGroupBy(splitterDef.splitColumn, job.query.filters)
          .map(counts => (splitterDef, counts))
      }
      .sequence
      .map[Either[NonEmptyList[String], Unit]] { ldc =>
        Monoid.combineAll(ldc.map {
          case (splitterDef, counts) =>
            if (splitterDef.numFolds != counts.size)
              s"Expected ${splitterDef.numFolds} folds, got ${counts.size}".leftNel[Unit]
            else {
              Monoid.combineAll(
                counts
                  .filter(_._2 == 0)
                  .map { case (value, _) => s"Fold $value has no data".leftNel[Unit] }
              )
            }
        })
      }
  }

  private def executeExperimentFlow(job: Job,
                                    splitters: List[FeaturesSplitter[F]],
                                    featuresTableService: FeaturesTableService[F],
                                    initiator: ActorRef): Future[Response] = {
    val algorithms = job.query.algorithms
    val experimentFlow: Flow[Job, Map[AlgorithmSpec, JobResult], NotUsed] =
      ExperimentFlow(
        featuresTableService,
        dispatcherService,
        splitters,
        algorithmExecutor,
        wokenWorker
      ).flow

    Source
      .single(job)
      .via(experimentFlow)
      .map { results =>
        logger.info("Experiment - build final response")
        logger.info(s"Algorithms: $algorithms")
        logger.info(s"Results: $results")

        assert(results.size == algorithms.size, "There should be as many results as algorithms")
        assert(results.keySet equals algorithms.toSet,
               "Algorithms defined in the results should match the incoming list of algorithms")

        val pfa =
          ExperimentJobResult(jobId = job.jobId, node = algorithmExecutor.node, results = results)

        Response(job, Right(pfa), initiator)
      }
      .runWith(Sink.head)
  }

  private def completeWithError(job: Job,
                                msg: String,
                                initiator: ActorRef,
                                replyTo: ActorRef): Unit = {
    val result =
      ErrorJobResult(Some(job.jobId), algorithmExecutor.node, OffsetDateTime.now(), None, msg)
    val _ = runNow(jobResultService.put(result))
    replyTo ! Response(job, Left(result), initiator)
    context stop self
  }

  private def completeExperiment(job: ExperimentJob,
                                 responseF: Future[Response],
                                 thisActor: ActorRef,
                                 initiator: ActorRef,
                                 replyTo: ActorRef): Unit =
    responseF
      .andThen {
        case Success(response) =>
          val result = response.result.fold(identity, identity)
          val _      = runNow(jobResultService.put(result))
          // Copy the job to avoid spilling internals of extended jobs to outside world. Smelly design here
          replyTo ! response.copy(job = job)
        case Failure(e) =>
          logger.error(s"Cannot complete experiment ${job.jobId}: ${e.getMessage}", e)
          val result = ErrorJobResult(Some(job.jobId),
                                      algorithmExecutor.node,
                                      OffsetDateTime.now(),
                                      None,
                                      e.toString)
          val response = Response(job, Left(result), initiator)
          val _        = runNow(jobResultService.put(result))
          replyTo ! response
      }
      .onComplete { _ =>
        logger.info("Stopping experiment...")
        Try {
          context stop thisActor
        }.recover {
          case NonFatal(e) =>
            logger.error("Cannot shutdown cleanly Experiment actor", e)
        }
      }
}

object ExperimentFlow {
  case class JobForAlgorithmPreparation(job: ExperimentJob,
                                        algorithmSpec: AlgorithmSpec,
                                        algorithmDefinition: AlgorithmDefinition,
                                        validations: List[ValidationSpec]) {
    assert(algorithmSpec.code == algorithmDefinition.code,
           "Algorithm spec and definition should match")
  }

}

// TODO: move featuresTableService, splitters to the job
case class ExperimentFlow[F[_]: Effect](
    featuresTableService: FeaturesTableService[F],
    dispatcherService: DispatcherService,
    splitters: List[FeaturesSplitter[F]],
    algorithmExecutor: AlgorithmExecutor[F],
    wokenWorker: WokenWorker[F]
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import ExperimentFlow.JobForAlgorithmPreparation

  private case class JobForAlgorithm(job: ExperimentJob,
                                     algorithmSpec: AlgorithmSpec,
                                     subJob: AlgorithmWithCVFlow.Job[F])

  private case class ExperimentJobResult(job: ExperimentJob,
                                         algorithmSpec: AlgorithmSpec,
                                         result: JobResult)

  private val algorithmWithCVFlow =
    AlgorithmWithCVFlow(algorithmExecutor, wokenWorker)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def flow: Flow[ExperimentJob, Map[AlgorithmSpec, JobResult], NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // TODO: detect distributed algorithms
        val LOCAL_PREDICTIVE_ALGORITHM = 0
        val OTHER_ALGORITHM            = 1

        def partitionAlgorithmByType(jobForAlgorithm: JobForAlgorithmPreparation): Int = {
          val algoDef = jobForAlgorithm.algorithmDefinition
          if (algoDef.predictive) {
            logger.info(s"Algorithm ${algoDef.code} is local and predictive")
            LOCAL_PREDICTIVE_ALGORITHM
          } else {
            logger.info(s"Algorithm ${algoDef.code} is not local or not predictive")
            OTHER_ALGORITHM
          }
        }

        // prepare graph elements
        val jobSplitter = builder.add(splitJob)
        val partition =
          builder
            .add(Partition[JobForAlgorithmPreparation](outputPorts = 2, partitionAlgorithmByType))
        val merge = builder.add(Merge[ExperimentJobResult](inputPorts = 2))
        val toMap =
          builder.add(Flow[ExperimentJobResult].fold[Map[AlgorithmSpec, JobResult]](Map()) {
            (m, r) =>
              m + (r.algorithmSpec -> r.result)
          })

        // connect the graph
        jobSplitter ~> partition.in
        // Algorithm with validation
        partition
          .out(LOCAL_PREDICTIVE_ALGORITHM) ~> prepareAlgorithmFlow ~> localAlgorithmWithValidation ~> merge
        // Algorithm without validation
        partition.out(OTHER_ALGORITHM) ~> prepareAlgorithmFlow ~> algorithmOnly ~> merge
        merge ~> toMap

        FlowShape(jobSplitter.in, toMap.out)
      })
      .named("run-experiment")

  def splitJob: Flow[ExperimentActor.Job, JobForAlgorithmPreparation, NotUsed] =
    Flow[ExperimentActor.Job]
      .map { job =>
        val algorithms  = job.queryAlgorithms
        val validations = job.query.validations

        algorithms.map {
          case (algoSpec, algoDefn) =>
            JobForAlgorithmPreparation(job, algoSpec, algoDefn, validations)
        }
      }
      .mapConcat(identity)
      .named("split-job")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def prepareAlgorithmFlow: Flow[JobForAlgorithmPreparation, JobForAlgorithm, NotUsed] =
    Flow[JobForAlgorithmPreparation]
      .map { a =>
        val job                 = a.job
        val jobId               = UUID.randomUUID().toString
        val query               = job.query
        val algorithmSpec       = a.algorithmSpec
        val algorithmDefinition = a.algorithmDefinition
        val featuresQuery       = query.features(job.inputTable, None)
        val subJob =
          createAlgoritmWithCVFlowJob(jobId, job, algorithmSpec, algorithmDefinition, featuresQuery)
        logger.info(s"Prepared mining query sub job $subJob")
        JobForAlgorithm(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  private def createAlgoritmWithCVFlowJob(jobId: String,
                                          job: ExperimentJob,
                                          algorithmSpec: AlgorithmSpec,
                                          algorithmDefinition: AlgorithmDefinition,
                                          query: FeaturesQuery) =
    AlgorithmWithCVFlow.Job(jobId,
                            algorithmSpec,
                            algorithmDefinition,
                            featuresTableService,
                            query,
                            splitters,
                            job.metadata)

  private def localAlgorithmWithValidation: Flow[JobForAlgorithm, ExperimentJobResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[JobForAlgorithm](outputPorts = 3))
        val runAlgorithmAndValidate =
          algorithmWithCVFlow.runLocalAlgorithmAndValidate(parallelism = 4)
        val zip = builder.add(ZipWith(ExperimentJobResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithmAndValidate
          .map(r => r.model.fold(identity, identity)) ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-validation")

  private def algorithmOnly: Flow[JobForAlgorithm, ExperimentJobResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast    = builder.add(Broadcast[JobForAlgorithm](outputPorts = 3))
        val runAlgorithm = algorithmWithCVFlow.runAlgorithmOnLocalData
        val takeModel    = Flow[AlgorithmResults].map(extractResult)
        val zip          = builder.add(ZipWith(ExperimentJobResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithm.map(_._2) ~> takeModel ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-only")

  private def extractResult(response: AlgorithmResults): JobResult = {
    val algorithm = response.job.algorithmSpec
    logger.info(s"Extract result from response: ${response.results}")
    response.results.headOption match {
      case Some(model) => model
      case None =>
        ErrorJobResult(Some(response.job.jobId),
                       node = algorithmExecutor.node,
                       OffsetDateTime.now(),
                       Some(algorithm.code),
                       "No results")
    }
  }

}
