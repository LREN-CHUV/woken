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
import akka.actor.{ Actor, ActorContext, ActorRef, Props }
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, ZipWith }
import cats.data.Validated._
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.config.JobsConfiguration
import ch.chuv.lren.woken.core.fp.runNow
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{ DispatcherService, FeaturesTableService }
import ch.chuv.lren.woken.validation.flows.AlgorithmWithCVFlow
import ch.chuv.lren.woken.validation.{
  FeaturesSplitter,
  FeaturesSplitterDefinition,
  defineSplitters
}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.language.higherKinds
import scala.util.{ Failure, Success }

/**
  * We use the companion object to hold all the messages that the ``ExperimentActor`` receives.
  */
object ExperimentActor {

  // Incoming messages
  type Job = ExperimentJob

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job,
                      result: Either[ErrorJobResult, ExperimentJobResult],
                      initiator: ActorRef)

  def props[F[_]: Effect](coordinatorConfig: CoordinatorConfig[F],
                          dispatcherService: DispatcherService): Props =
    Props(new ExperimentActor(coordinatorConfig, dispatcherService))

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor[F[_]: Effect](val coordinatorConfig: CoordinatorConfig[F],
                                    val dispatcherService: DispatcherService)
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
      val replyTo    = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo
      val thisActor  = self
      val algorithms = job.query.algorithms
      val featuresTableServiceV =
        coordinatorConfig.featuresService.featuresTable(job.inputTable)

      logger.info(s"Start new experiment job $job")
      logger.info(s"List of algorithms: ${algorithms.mkString(",")}")

      val containsPredictiveAlgorithms = job.queryAlgorithms.exists {
        case (_, defn) => defn.predictive
      }

      val splitterDefsV: Validation[List[FeaturesSplitterDefinition]] =
        if (containsPredictiveAlgorithms) {
          defineSplitters(job.query.validations)
        } else Nil.validNel[String]

      ((featuresTableServiceV, splitterDefsV) mapN Tuple2.apply).fold(
        err => {
          val msg = s"""Invalid definition of validations: ${err.mkString_("", ",", "")}"""
          completeWithError(job, msg, initiator, replyTo)
        }, {
          case (featuresTableService, splitterDefs) =>
            if (splitterDefs.isEmpty)
              executeExperimentFlow(job, Nil, featuresTableService, thisActor, initiator, replyTo)
            else
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
                  extendedFeaturesTableR => {
                    extendedFeaturesTableR.use[Unit] {
                      extendedFeaturesTable =>
                        val splitters = splitterDefs.map {
                          FeaturesSplitter(_, extendedFeaturesTable)
                        }

                        val extTable = extendedFeaturesTable.table.table
                        val extendedJob = job.copy(
                          inputTable = extTable,
                          query = job.query.copy(targetTable = Some(extTable.name))
                        )

                        val future = executeExperimentFlow(extendedJob,
                                                           splitters,
                                                           extendedFeaturesTable,
                                                           thisActor,
                                                           initiator,
                                                           replyTo)

                        Effect[F].async[Unit] { cb =>
                          // Success and failure cases have been already handled
                          future.onSuccess { case _   => cb(Right(())) }
                          future.onFailure { case err => cb(Left(err)) }
                        }
                    }
                  }
                )
        }
      )

    case e =>
      logger.error(s"Unhandled message: $e")
      context stop self
  }

  private def executeExperimentFlow(job: Job,
                                    splitters: List[FeaturesSplitter[F]],
                                    featuresTableService: FeaturesTableService[F],
                                    thisActor: ActorRef,
                                    initiator: ActorRef,
                                    replyTo: ActorRef): Future[Response] = {
    val algorithms = job.query.algorithms
    val experimentFlow: Flow[Job, Map[AlgorithmSpec, JobResult], NotUsed] =
      ExperimentFlow(
        CoordinatorActor.executeJobAsync(coordinatorConfig, context),
        featuresTableService,
        coordinatorConfig.jobsConf,
        dispatcherService,
        splitters,
        context
      ).flow

    val future: Future[Response] = Source
      .single(job)
      .via(experimentFlow)
      .map { results =>
        logger.info("Experiment - build final response")
        logger.info(s"Algorithms: $algorithms")
        logger.info(s"Results: $results")

        assert(results.size == algorithms.size, "There should be as many results as algorithms")
        assert(results.keySet equals algorithms.toSet,
               "Algorithms defined in the results should match the incoming list of algorithms")

        val pfa = ExperimentJobResult(jobId = job.jobId,
                                      node = coordinatorConfig.jobsConf.node,
                                      results = results)

        Response(job, Right(pfa), initiator)
      }
      .runWith(Sink.head)

    completeExperiment(job, future, thisActor, initiator, replyTo)

    future
  }

  private def completeWithError(job: Job,
                                msg: String,
                                initiator: ActorRef,
                                replyTo: ActorRef): Unit = {
    val result = ErrorJobResult(Some(job.jobId),
                                coordinatorConfig.jobsConf.node,
                                OffsetDateTime.now(),
                                None,
                                msg)
    val _ = runNow(coordinatorConfig.jobResultService.put(result))
    replyTo ! Response(job, Left(result), initiator)
    context stop self
  }

  private def completeExperiment(job: ExperimentActor.Job,
                                 responseF: Future[Response],
                                 thisActor: ActorRef,
                                 initiator: ActorRef,
                                 replyTo: ActorRef): Unit =
    responseF
      .andThen {
        case Success(response) =>
          val result = response.result.fold(identity, identity)
          val _      = runNow(coordinatorConfig.jobResultService.put(result))
          replyTo ! response
        case Failure(e) =>
          logger.error(s"Cannot complete experiment ${job.jobId}: ${e.getMessage}", e)
          val result = ErrorJobResult(Some(job.jobId),
                                      coordinatorConfig.jobsConf.node,
                                      OffsetDateTime.now(),
                                      None,
                                      e.toString)
          val response = Response(job, Left(result), initiator)
          val _        = runNow(coordinatorConfig.jobResultService.put(result))
          replyTo ! response
      }
      .onComplete { _ =>
        logger.info("Stopping experiment...")
        context stop thisActor
      }
}

object ExperimentFlow {
  case class JobForAlgorithmPreparation(job: ExperimentActor.Job,
                                        algorithmSpec: AlgorithmSpec,
                                        algorithmDefinition: AlgorithmDefinition,
                                        validations: List[ValidationSpec]) {
    assert(algorithmSpec.code == algorithmDefinition.code,
           "Algorithm spec and definition should match")
  }

}

case class ExperimentFlow[F[_]: Effect](
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresTableService: FeaturesTableService[F],
    jobsConf: JobsConfiguration,
    dispatcherService: DispatcherService,
    splitters: List[FeaturesSplitter[F]],
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import ExperimentFlow.JobForAlgorithmPreparation

  private case class JobForAlgorithm(job: ExperimentJob,
                                     algorithmSpec: AlgorithmSpec,
                                     subJob: AlgorithmWithCVFlow.Job[F])

  private case class AlgorithmResult(job: ExperimentJob,
                                     algorithmSpec: AlgorithmSpec,
                                     result: JobResult)

  private val algorithmWithCVFlow =
    AlgorithmWithCVFlow(executeJobAsync, featuresTableService, jobsConf, context)

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
        val merge = builder.add(Merge[AlgorithmResult](inputPorts = 2))
        val toMap =
          builder.add(Flow[AlgorithmResult].fold[Map[AlgorithmSpec, JobResult]](Map()) { (m, r) =>
            m + (r.algorithmSpec -> r.result)
          })

        // connect the graph
        jobSplitter ~> partition.in
        // Algorithm with validation
        partition
          .out(LOCAL_PREDICTIVE_ALGORITHM) ~> prepareMiningQuery ~> localAlgorithmWithValidation ~> merge
        // Algorithm without validation
        partition.out(OTHER_ALGORITHM) ~> prepareMiningQuery ~> algorithmOnly ~> merge
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
  private def prepareMiningQuery: Flow[JobForAlgorithmPreparation, JobForAlgorithm, NotUsed] =
    Flow[JobForAlgorithmPreparation]
      .map { a =>
        val job                 = a.job
        val jobId               = UUID.randomUUID().toString
        val query               = job.query
        val algorithmSpec       = a.algorithmSpec
        val algorithmDefinition = a.algorithmDefinition
        val miningQuery         = createMiningQuery(job, query, algorithmSpec)
        val subJob              = createAlgoritmWithCVFlowJob(jobId, job, algorithmDefinition, miningQuery)
        logger.info(s"Prepared mining query sub job $subJob")
        JobForAlgorithm(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  private def createAlgoritmWithCVFlowJob(jobId: String,
                                          job: ExperimentJob,
                                          algorithmDefinition: AlgorithmDefinition,
                                          miningQuery: MiningQuery) =
    AlgorithmWithCVFlow.Job(jobId,
                            job.inputTable,
                            miningQuery,
                            splitters,
                            job.metadata,
                            algorithmDefinition)

  private def createMiningQuery(job: ExperimentJob,
                                query: ExperimentQuery,
                                algorithmSpec: AlgorithmSpec) =
    MiningQuery(
      user = query.user,
      variables = query.variables,
      covariables = query.covariables,
      covariablesMustExist = query.covariablesMustExist,
      grouping = query.grouping,
      filters = query.filters,
      targetTable = Some(job.inputTable.name),
      datasets = query.trainingDatasets,
      algorithm = algorithmSpec,
      executionPlan = None
    )

  private def localAlgorithmWithValidation: Flow[JobForAlgorithm, AlgorithmResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[JobForAlgorithm](outputPorts = 3))
        val runAlgorithmAndValidate =
          algorithmWithCVFlow.runLocalAlgorithmAndValidate(parallelism = 4)
        val zip = builder.add(ZipWith(AlgorithmResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithmAndValidate
          .map(r => r.model.fold(identity, identity)) ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-validation")

  private def algorithmOnly: Flow[JobForAlgorithm, AlgorithmResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast    = builder.add(Broadcast[JobForAlgorithm](outputPorts = 3))
        val runAlgorithm = algorithmWithCVFlow.runAlgorithmOnLocalData
        val takeModel    = Flow[CoordinatorActor.Response].map(extractResult)
        val zip          = builder.add(ZipWith(AlgorithmResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithm.map(_._2) ~> takeModel ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-only")

  private def extractResult(response: CoordinatorActor.Response): JobResult = {
    val algorithm = response.job.algorithmSpec
    logger.info(s"Extract result from response: ${response.results}")
    response.results.headOption match {
      case Some(model) => model
      case None =>
        ErrorJobResult(Some(response.job.jobId),
                       node = jobsConf.node,
                       OffsetDateTime.now(),
                       Some(algorithm.code),
                       "No results")
    }
  }

}
