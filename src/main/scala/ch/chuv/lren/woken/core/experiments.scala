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
import akka.actor.{ Actor, ActorContext, ActorLogging, Props }
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, Zip }
import cats.data.NonEmptyList
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.core.validation.ValidatedAlgorithmFlow

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }
import ch.chuv.lren.woken.core.commands.JobCommands.StartExperimentJob
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, JobResult, PfaExperimentJobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.query.{
  AlgorithmSpec,
  ExperimentQuery,
  MiningQuery,
  ValidationSpec
}

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
  )

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job, result: Either[ErrorJobResult, PfaExperimentJobResult])

  def props(coordinatorConfig: CoordinatorConfig,
            algorithmLookup: String => Validation[AlgorithmDefinition]): Props =
    Props(new ExperimentActor(coordinatorConfig, algorithmLookup))

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
    with ActorLogging {

  import ExperimentActor._

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      log.error(err, "Runtime error detected")
      Supervision.Resume
    case err =>
      log.error(err, "Unknown error. Stopping the stream.")
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
      coordinatorConfig.jobsConf.node,
      algorithmLookup,
      context
    ).flow

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.NonUnitStatements"))
  override def receive: PartialFunction[Any, Unit] = {
    case StartExperimentJob(job) if job.query.algorithms.isEmpty =>
      val initiator = sender()
      val msg       = "Experiment contains no algorithms"
      val result = ErrorJobResult(job.jobId,
                                  coordinatorConfig.jobsConf.node,
                                  OffsetDateTime.now(),
                                  "experiment",
                                  msg)
      coordinatorConfig.jobResultService.put(result)
      initiator ! Response(job, Left(result))
      context stop self

    case StartExperimentJob(job) if job.query.algorithms.nonEmpty =>
      val initiator  = sender()
      val thisActor  = self
      val algorithms = job.query.algorithms

      log.info("Start new experiment job")
      log.info(s"List of algorithms: ${algorithms.mkString(",")}")

      val future = Source
        .single(job)
        .via(experimentFlow)
        .runWith(Sink.head)
        .map { results =>
          log.info("Experiment - build final response")
          log.info(s"Algorithms: $algorithms")
          log.info(s"Results: $results")

          assert(results.size == algorithms.size, "There should be as many results as algorithms")
          assert(results.keySet equals algorithms.toSet,
                 "Algorithms defined in the results should match the incoming list of algorithms")

          val pfa = PfaExperimentJobResult(experimentJobId = job.jobId,
                                           experimentNode = coordinatorConfig.jobsConf.node,
                                           results = results)

          Response(job, Right(pfa))
        }

      future
        .andThen {
          case Success(r) =>
            coordinatorConfig.jobResultService.put(r.result.fold(identity, identity))
            initiator ! r
          case Failure(f) =>
            log.error(f, s"Cannot complete experiment ${job.jobId}: ${f.getMessage}")
            val result = ErrorJobResult(job.jobId,
                                        coordinatorConfig.jobsConf.node,
                                        OffsetDateTime.now(),
                                        "experiment",
                                        f.toString)
            val response = Response(job, Left(result))
            coordinatorConfig.jobResultService.put(result)
            initiator ! response
        }
        .onComplete { _ =>
          log.info("Stopping...")
          context stop thisActor
        }

    case e =>
      log.error(s"Unhandled message: $e")
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
    node: String,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private case class AlgorithmValidation(job: ExperimentActor.Job,
                                         algorithmSpec: AlgorithmSpec,
                                         subJob: ValidatedAlgorithmFlow.Job)

  private val validatedAlgorithmFlow =
    ValidatedAlgorithmFlow(executeJobAsync, featuresDatabase, context)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def flow: Flow[ExperimentActor.Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val jobSplitter = builder.add(splitJob)
        val partition = builder.add(
          Partition[AlgorithmValidationMaybe](
            3,
            partitioner = algoMaybe => {
              algoMaybe.algorithmDefinition.fold(
                { _: NonEmptyList[String] =>
                  2
                }, { algoDef: AlgorithmDefinition =>
                  if (algoDef.predictive) 0 else 1
                }
              )
            }
          )
        )
        val merge = builder.add(Merge[(AlgorithmSpec, JobResult)](3))
        val toMap =
          builder.add(Flow[(AlgorithmSpec, JobResult)].fold[Map[AlgorithmSpec, JobResult]](Map()) {
            (m, r) =>
              m + r
          })

        // connect the graph
        jobSplitter ~> partition.in
        // Algorithm with validation
        partition.out(0) ~> prepareMiningQuery ~> algorithmWithValidation ~> merge
        // Algorithm without validation
        partition.out(1) ~> prepareMiningQuery ~> algorithmOnly ~> merge
        // Invalid algorithm
        partition.out(2) ~> failJob ~> merge
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
  private def failJob: Flow[AlgorithmValidationMaybe, (AlgorithmSpec, JobResult), NotUsed] =
    Flow[AlgorithmValidationMaybe]
      .map { a =>
        val errorMessage = a.algorithmDefinition.toEither.left.get
        a.algorithmSpec -> ErrorJobResult(UUID.randomUUID().toString,
                                          node,
                                          OffsetDateTime.now(),
                                          a.algorithmSpec.code,
                                          errorMessage.reduceLeft(_ + ", " + _))
      }
      .named("fail-job")

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
        AlgorithmValidation(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  private def algorithmWithValidation
    : Flow[AlgorithmValidation, (AlgorithmSpec, JobResult), NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast               = builder.add(Broadcast[AlgorithmValidation](2))
        val takeSpec                = Flow[AlgorithmValidation].map(_.algorithmSpec)
        val takeSubjob              = Flow[AlgorithmValidation].map(_.subJob)
        val runAlgorithmAndValidate = validatedAlgorithmFlow.runAlgorithmAndValidate(4)
        val takeModel               = Flow[ValidatedAlgorithmFlow.ResultResponse].map(_.model)
        val zip                     = builder.add(Zip[AlgorithmSpec, JobResult]())

        broadcast.out(0) ~> takeSpec ~> zip.in0
        broadcast.out(1) ~> takeSubjob ~> runAlgorithmAndValidate ~> takeModel ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-validation")

  private def algorithmOnly: Flow[AlgorithmValidation, (AlgorithmSpec, JobResult), NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast    = builder.add(Broadcast[AlgorithmValidation](2))
        val takeSpec     = Flow[AlgorithmValidation].map(_.algorithmSpec)
        val takeSubjob   = Flow[AlgorithmValidation].map(_.subJob)
        val runAlgorithm = validatedAlgorithmFlow.runAlgoInDocker
        val takeModel    = Flow[CoordinatorActor.Response].map(extractResult)
        val zip          = builder.add(Zip[AlgorithmSpec, JobResult]())

        broadcast.out(0) ~> takeSpec ~> zip.in0
        broadcast.out(1) ~> takeSubjob ~> runAlgorithm ~> takeModel ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-only")

  private def extractResult(response: CoordinatorActor.Response): JobResult = {
    val algorithm = response.job.algorithmSpec
    response.results.headOption match {
      case Some(model) => model
      case None =>
        ErrorJobResult(response.job.jobId,
                       node = "",
                       OffsetDateTime.now(),
                       algorithm.code,
                       "No results")
    }
  }

}
