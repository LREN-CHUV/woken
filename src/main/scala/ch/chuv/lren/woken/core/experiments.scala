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

package ch.chuv.lren.woken.core

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.{ Actor, ActorContext, ActorRef, Props }
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, ZipWith }
import cats.data.NonEmptyList
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.core.validation.ValidatedAlgorithmFlow

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }
import ch.chuv.lren.woken.core.commands.JobCommands.StartExperimentJob
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.scalalogging.LazyLogging

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
      metadata: List[VariableMetaData]
  ) extends model.Job

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job,
                      result: Either[ErrorJobResult, ExperimentJobResult],
                      initiator: ActorRef)

  def props(coordinatorConfig: CoordinatorConfig,
            algorithmLookup: String => Validation[AlgorithmDefinition],
            dispatcherService: DispatcherService): Props =
    Props(new ExperimentActor(coordinatorConfig, algorithmLookup, dispatcherService))

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val coordinatorConfig: CoordinatorConfig,
                      algorithmLookup: String => Validation[AlgorithmDefinition],
                      dispatcherService: DispatcherService)
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

  lazy val experimentFlow: Flow[Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    ExperimentFlow(
      CoordinatorActor.executeJobAsync(coordinatorConfig, context),
      coordinatorConfig.featuresDatabase,
      coordinatorConfig.jobsConf,
      algorithmLookup,
      dispatcherService,
      context
    ).flow

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.NonUnitStatements"))
  override def receive: Receive = {
    case StartExperimentJob(job, requestedReplyTo, initiator) if job.query.algorithms.isEmpty =>
      val replyTo = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo
      val msg     = "Experiment contains no algorithms"
      val result = ErrorJobResult(Some(job.jobId),
                                  coordinatorConfig.jobsConf.node,
                                  OffsetDateTime.now(),
                                  None,
                                  msg)
      coordinatorConfig.jobResultService.put(result)
      replyTo ! Response(job, Left(result), initiator)
      context stop self

    case StartExperimentJob(job, requestedReplyTo, initiator) if job.query.algorithms.nonEmpty =>
      val replyTo    = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo
      val thisActor  = self
      val algorithms = job.query.algorithms

      logger.info(s"Start new experiment job $job")
      logger.info(s"List of algorithms: ${algorithms.mkString(",")}")

      val future = Source
        .single(job)
        .via(experimentFlow)
        .runWith(Sink.head)
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

      future
        .andThen {
          case Success(response) =>
            val result = response.result.fold(identity, identity)
            coordinatorConfig.jobResultService.put(result)
            replyTo ! response
          case Failure(e) =>
            logger.error(s"Cannot complete experiment ${job.jobId}: ${e.getMessage}", e)
            val result = ErrorJobResult(Some(job.jobId),
                                        coordinatorConfig.jobsConf.node,
                                        OffsetDateTime.now(),
                                        None,
                                        e.toString)
            val response = Response(job, Left(result), initiator)
            coordinatorConfig.jobResultService.put(result)
            replyTo ! response
        }
        .onComplete { _ =>
          logger.info("Stopping...")
          context stop thisActor
        }

    case e =>
      logger.error(s"Unhandled message: $e")
      context stop self
  }

}

case class AlgorithmValidationMaybe(job: ExperimentActor.Job,
                                    algorithmSpec: AlgorithmSpec,
                                    algorithmDefinition: Validation[AlgorithmDefinition],
                                    validations: List[ValidationSpec])
case class ExperimentFlow(
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    jobsConf: JobsConfiguration,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    dispatcherService: DispatcherService,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  private case class AlgorithmValidation(job: ExperimentActor.Job,
                                         algorithmSpec: AlgorithmSpec,
                                         subJob: ValidatedAlgorithmFlow.Job)

  private case class AlgorithmResult(job: ExperimentActor.Job,
                                     algorithmSpec: AlgorithmSpec,
                                     result: JobResult)

  private val validatedAlgorithmFlow =
    ValidatedAlgorithmFlow(executeJobAsync, featuresDatabase, jobsConf, context)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def flow: Flow[ExperimentActor.Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // TODO: detect distributed algorithms
        val LOCAL_PREDICTIVE_ALGORITHM = 0
        val OTHER_ALGORITHM            = 1
        val INVALID_ALGORITHM          = 2

        def partitionAlgorithmByType(algoMaybe: AlgorithmValidationMaybe): Int =
          algoMaybe.algorithmDefinition.fold(
            { _: NonEmptyList[String] =>
              INVALID_ALGORITHM
            }, { algoDef: AlgorithmDefinition =>
              if (algoDef.predictive) {
                logger.info(s"Algorithm ${algoDef.code} is local and predictive")
                LOCAL_PREDICTIVE_ALGORITHM
              } else {
                logger.info(s"Algorithm ${algoDef.code} is not local or not predictive")
                OTHER_ALGORITHM
              }
            }
          )

        // prepare graph elements
        val jobSplitter = builder.add(splitJob)
        val partition =
          builder.add(Partition[AlgorithmValidationMaybe](3, partitionAlgorithmByType))
        val merge = builder.add(Merge[AlgorithmResult](3))
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
        // Invalid algorithm
        partition.out(INVALID_ALGORITHM) ~> failedJob ~> merge
        merge ~> toMap

        FlowShape(jobSplitter.in, toMap.out)
      })
      .named("run-experiment")

  def splitJob: Flow[ExperimentActor.Job, AlgorithmValidationMaybe, NotUsed] =
    Flow[ExperimentActor.Job]
      .map { job =>
        val algorithms  = job.query.algorithms
        val validations = job.query.validations

        algorithms.map(a => AlgorithmValidationMaybe(job, a, algorithmLookup(a.code), validations))
      }
      .mapConcat(identity)
      .named("split-job")

  @SuppressWarnings(Array("org.wartremover.warts.EitherProjectionPartial"))
  private def failedJob: Flow[AlgorithmValidationMaybe, AlgorithmResult, NotUsed] =
    Flow[AlgorithmValidationMaybe]
      .map { a =>
        val errorMessage = a.algorithmDefinition.toEither.left.get
        val jobResult = ErrorJobResult(Some(a.job.jobId),
                                       jobsConf.node,
                                       OffsetDateTime.now(),
                                       Some(a.algorithmSpec.code),
                                       errorMessage.reduceLeft(_ + ", " + _))
        AlgorithmResult(a.job, a.algorithmSpec, jobResult)
      }
      .named("failed-job")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def prepareMiningQuery: Flow[AlgorithmValidationMaybe, AlgorithmValidation, NotUsed] =
    Flow[AlgorithmValidationMaybe]
      .map { a =>
        val job           = a.job
        val jobId         = UUID.randomUUID().toString
        val query         = job.query
        val algorithmSpec = a.algorithmSpec
        val algorithmDefinition = a.algorithmDefinition.getOrElse(
          throw new IllegalStateException("Expected a valid definition")
        )
        val validations = if (algorithmDefinition.predictive) a.validations else Nil
        val miningQuery = MiningQuery(
          user = query.user,
          variables = query.variables,
          covariables = query.covariables,
          grouping = query.grouping,
          filters = query.filters,
          targetTable = Some(job.inputTable),
          datasets = query.trainingDatasets,
          algorithm = algorithmSpec,
          executionPlan = None
        )
        val subJob = ValidatedAlgorithmFlow.Job(jobId,
                                                job.inputDb,
                                                job.inputTable,
                                                miningQuery,
                                                job.metadata,
                                                validations,
                                                algorithmDefinition)
        logger.info(s"Prepared mining query sub job $subJob")
        AlgorithmValidation(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  private def localAlgorithmWithValidation: Flow[AlgorithmValidation, AlgorithmResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast               = builder.add(Broadcast[AlgorithmValidation](3))
        val runAlgorithmAndValidate = validatedAlgorithmFlow.runLocalAlgorithmAndValidate(4)
        val zip                     = builder.add(ZipWith(AlgorithmResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithmAndValidate
          .map(r => r.model.fold(identity, identity)) ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-validation")

  private def algorithmOnly: Flow[AlgorithmValidation, AlgorithmResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast    = builder.add(Broadcast[AlgorithmValidation](3))
        val runAlgorithm = validatedAlgorithmFlow.runAlgorithmOnLocalData
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
