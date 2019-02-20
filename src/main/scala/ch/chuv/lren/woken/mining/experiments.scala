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
import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, ZipWith }
import cats.Monoid
import cats.data.NonEmptyList
import cats.data.Validated._
import cats.effect.{ Effect, Resource }
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.{ AlgorithmExecutor, AlgorithmResults }
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.core.fp._
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.JobResultRepository
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.mining.LocalExperimentFlow.LocalExperimentJob
import ch.chuv.lren.woken.service.{ FeaturesService, FeaturesTableService }
import ch.chuv.lren.woken.validation.flows.AlgorithmWithCVFlow
import ch.chuv.lren.woken.validation.{
  FeaturesSplitter,
  FeaturesSplitterDefinition,
  defineSplitters
}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.control.NonFatal

object LocalExperimentService {
  case class LocalExperimentResponse(jobInProgress: ExperimentJobInProgress,
                                     result: Either[ErrorJobResult, ExperimentJobResult]) {
    def toQueryResult: QueryResult =
      result
        .fold(r => r, r => r)
        .asQueryResult(jobInProgress.job.query,
                       jobInProgress.dataProvenance,
                       jobInProgress.feedback)
  }

}

/**
  * This service manages the local execution of an experiment
  */
case class LocalExperimentService[F[_]: Effect](
    algorithmExecutor: AlgorithmExecutor[F],
    wokenWorker: WokenWorker[F],
    featuresService: FeaturesService[F],
    jobResultService: JobResultRepository[F],
    system: ActorSystem
)(implicit ctx: ActorRefFactory, ec: ExecutionContext)
    extends LazyLogging {
  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error("Runtime error detected", err)
      Supervision.Resume
    case err =>
      logger.error("Unknown error. Stopping the stream.", err)
      Supervision.Stop
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  )

  import LocalExperimentService._

  // Implementation note: when cross-validation is used, we create a temporary table in the database. We ensure the release of resources using Cats Resource.
  // It is not possible to combine Akka flows with Cats Resource, so instead of creating an end-to-end flow for the computation, we wrap the flow into
  // a F effect where resource management is properly applied.

  /**
    * Run the experiment locally: each algorithm contained in the experiment is executed and its results are cross-validated if applicable
    *
    * @param job The job in progress to execute
    * @return a monad wrapping the computation for the response
    */
  def runExperiment(job: ExperimentJobInProgress): F[LocalExperimentResponse] =
    if (job.job.queryAlgorithms.isEmpty) {
      errorResult(job, "Experiment contains no algorithms").pure[F]
    } else {
      val query      = job.job.query
      val algorithms = query.algorithms
      val featuresTableServiceV =
        featuresService.featuresTable(job.job.inputTable)

      val containsPredictiveAlgorithms = job.job.queryAlgorithms.exists {
        case (_, defn) => defn.predictive
      }

      val splitterDefsV: Validation[List[FeaturesSplitterDefinition]] =
        if (containsPredictiveAlgorithms) {
          defineSplitters(job.job.query.validations)
        } else Nil.validNel[String]

      logger.info(
        s"Start new experiment job $job ${if (containsPredictiveAlgorithms) "with" else "without"} predictive algorithms"
      )
      logger.info(s"List of algorithms: ${algorithms.mkString(",")}")

      ((featuresTableServiceV, splitterDefsV) mapN Tuple2.apply).fold(
        err => {
          val msg = s"""Invalid definition of cross-validations: ${err.mkString_(",")}"""
          errorResult(job, msg).pure[F]
        },
        tableAndSplits => safeExecutionOfExperiment(job, tableAndSplits)
      )
    }

  private def safeExecutionOfExperiment(
      job: ExperimentJobInProgress,
      tableAndSplits: (FeaturesTableService[F], List[FeaturesSplitterDefinition])
  ): F[LocalExperimentResponse] = {

    val (featuresTableService, splitterDefs) = tableAndSplits
    val r: F[LocalExperimentResponse] =
      if (splitterDefs.isEmpty)
        executeExperimentFlow(job, Nil, featuresTableService)
      else
        prepareAndExecuteExtendedExperimentFlow(job, splitterDefs, featuresTableService)

    r.recoverWith(recoverErrors(job))
      .flatMap { response =>
        // Intersperse a side effect to store the result
        jobResultService.put(response.result.fold(r => r, r => r)).map(_ => response)
      }
  }

  private def recoverErrors(
      job: ExperimentJobInProgress
  ): PartialFunction[Throwable, F[LocalExperimentResponse]] = {
    case NonFatal(e) =>
      Effect[F].delay {
        val msg = s"Cannot complete experiment ${job.job.jobId}: ${e.getMessage}"
        logger.error(msg, e)
        errorResult(job, msg)
      }
  }

  private def errorResult(job: ExperimentJobInProgress, msg: String): LocalExperimentResponse = {
    val result =
      ErrorJobResult(Some(job.job.jobId), algorithmExecutor.node, OffsetDateTime.now(), None, msg)
    LocalExperimentResponse(job, Left(result))
  }

  implicit val FPlus: Monoid[Either[NonEmptyList[String], Unit]] =
    new Monoid[Either[NonEmptyList[String], Unit]] {
      def empty: Either[NonEmptyList[String], Unit] = ().rightNel[String]
      def combine(
          x: Either[NonEmptyList[String], Unit],
          y: Either[NonEmptyList[String], Unit]
      ): Either[NonEmptyList[String], Unit] =
        x.combine(y)
    }

  private[mining] def validateFolds(
      job: ExperimentJob,
      splitterDefs: List[FeaturesSplitterDefinition],
      extendedFeaturesTable: FeaturesTableService[F]
  ): F[Either[NonEmptyList[String], Unit]] =
    splitterDefs
      .map { splitterDef =>
        extendedFeaturesTable
          .countGroupBy(splitterDef.splitColumn, job.query.filters)
          .map(counts => (splitterDef, counts))
      }
      .sequence
      .map[Either[NonEmptyList[String], Unit]] { ldc =>
        Monoid.combineAll(ldc.map {
          case (splitterDef, counts) if splitterDef.numFolds != counts.size =>
            s"Expected ${splitterDef.numFolds} folds, got ${counts.size}".leftNel[Unit]
          case (_, counts) =>
            Monoid.combineAll(
              counts
                .filter(_._2 == 0)
                .map { case (value, _) => s"Fold $value has no data".leftNel[Unit] }
            )
        })
      }

  private def executeExperimentFlow(
      job: ExperimentJobInProgress,
      splitters: List[FeaturesSplitter[F]],
      featuresTableService: FeaturesTableService[F]
  ): F[LocalExperimentResponse] = {
    val algorithms     = job.job.query.algorithms
    val experimentFlow = LocalExperimentFlow(algorithmExecutor, wokenWorker).flow

    Source
      .single(LocalExperimentJob(job.job, featuresTableService, splitters))
      .via(experimentFlow)
      .map { results =>
        logger.info("Experiment - build final response")
        logger.info(s"Algorithms: $algorithms")
        logger.debug(s"Results: $results")

        assert(results.size == algorithms.size, "There should be as many results as algorithms")
        assert(results.keySet equals algorithms.toSet,
               "Algorithms defined in the results should match the incoming list of algorithms")

        val pfa =
          ExperimentJobResult(jobId = job.job.jobId,
                              node = algorithmExecutor.node,
                              results = results)

        LocalExperimentResponse(job, Right(pfa))
      }
      .runWith(Sink.head)
      .fromFuture
  }

  private def prepareAndExecuteExtendedExperimentFlow(
      job: ExperimentJobInProgress,
      splitterDefs: List[FeaturesSplitterDefinition],
      featuresTableService: FeaturesTableService[F]
  ): F[LocalExperimentResponse] =
    featuresTableService
      .createExtendedFeaturesTable(job.job.query.filters,
                                   Nil,
                                   splitterDefs.map(_.splitColumn),
                                   splitterDefs)
      .fold(
        err => {
          val msg =
            s"""Invalid definition of extended features table: ${err
              .mkString_("", ",", "")}"""
          errorResult(job, msg).pure[F]
        },
        (extendedFeaturesTableR: Resource[F, FeaturesTableService[F]]) => {
          logger.info("Use extended features table")
          extendedFeaturesTableR.use[LocalExperimentResponse] { extendedFeaturesTable =>
            executeExtendedExperimentFlow(job, splitterDefs, extendedFeaturesTable)
          }
        }
      )

  private def executeExtendedExperimentFlow(
      job: ExperimentJobInProgress,
      splitterDefs: List[FeaturesSplitterDefinition],
      extendedFeaturesTable: FeaturesTableService[F]
  ): F[LocalExperimentResponse] = {
    val splitters = splitterDefs.map {
      FeaturesSplitter(_, extendedFeaturesTable)
    }

    val extTable = extendedFeaturesTable.table.table
    val extendedJob = job.job.copy(
      inputTable = extTable,
      query = job.job.query.copy(targetTable = Some(extTable))
    )
    val extendedJobInProgress: ExperimentJobInProgress = job.copy(job = extendedJob)

    logger.info("Validate folds created in extended feature table")
    validateFolds(extendedJob, splitterDefs, extendedFeaturesTable)
      .flatMap {
        case Left(err) =>
          val msg =
            s"""Invalid folding of extended features table: ${err
              .mkString_(",")}"""
          errorResult(job, msg).pure[F]
        case Right(_) =>
          logger.info("Execute experiment backed by extended feature table")
          executeExperimentFlow(extendedJobInProgress, splitters, extendedFeaturesTable)
      }
      .map { r =>
        // Restore the original job in the response
        r.copy(jobInProgress = job)
      }
  }
}

private[mining] object LocalExperimentFlow extends LazyLogging {

  case class LocalExperimentJob[F[_]](job: ExperimentJob,
                                      featuresTableService: FeaturesTableService[F],
                                      splitters: List[FeaturesSplitter[F]])

  case class AlgorithmInExperimentPreparation[F[_]](
      job: ExperimentJob,
      algorithmSpec: AlgorithmSpec,
      algorithmDefinition: AlgorithmDefinition,
      validations: List[ValidationSpec],
      featuresTableService: FeaturesTableService[F],
      splitters: List[FeaturesSplitter[F]]
  ) {
    assert(algorithmSpec.code == algorithmDefinition.code,
           "Algorithm spec and definition should match")
  }

  case class AlgorithmInExperimentJob[F[_]](job: ExperimentJob,
                                            algorithmSpec: AlgorithmSpec,
                                            subJob: AlgorithmWithCVFlow.Job[F])

  case class AlgorithmInExperimentResult(job: ExperimentJob,
                                         algorithmSpec: AlgorithmSpec,
                                         result: JobResult)

}

private[mining] case class LocalExperimentFlow[F[_]: Effect](
    algorithmExecutor: AlgorithmExecutor[F],
    wokenWorker: WokenWorker[F]
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import LocalExperimentFlow._

  private val algorithmWithCVFlow =
    AlgorithmWithCVFlow(algorithmExecutor, wokenWorker)

  private val LOCAL_PREDICTIVE_ALGORITHM = 0
  private val OTHER_ALGORITHM            = 1

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def flow: Flow[LocalExperimentJob[F], Map[AlgorithmSpec, JobResult], NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val splitByAlgorithm = builder.add(splitExperimentJobByAlgorithm)
        val partition = builder
          .add(
            Partition[AlgorithmInExperimentPreparation[F]](outputPorts = 2,
                                                           partitionAlgorithmByType)
          )
        val merge = builder.add(Merge[AlgorithmInExperimentResult](inputPorts = 2))
        val toMap =
          builder.add(Flow[AlgorithmInExperimentResult].fold(Map[AlgorithmSpec, JobResult]()) {
            (m, r) =>
              m + (r.algorithmSpec -> r.result)
          })

        // connect the graph
        splitByAlgorithm ~> partition.in
        // Algorithm with validation
        partition
          .out(LOCAL_PREDICTIVE_ALGORITHM) ~> prepareAlgorithmFlow ~> runLocalAlgorithmWithCrossValidation ~> merge
        // Algorithm without validation
        partition.out(OTHER_ALGORITHM) ~> prepareAlgorithmFlow ~> runLocalAlgorithm ~> merge
        merge ~> toMap

        FlowShape(splitByAlgorithm.in, toMap.out)
      })
      .named("run-experiment")

  private def partitionAlgorithmByType(
      jobForAlgorithm: AlgorithmInExperimentPreparation[F]
  ): Int = {
    val algoDef = jobForAlgorithm.algorithmDefinition
    if (algoDef.predictive) {
      logger.info(s"Algorithm ${algoDef.code} is local and predictive")
      LOCAL_PREDICTIVE_ALGORITHM
    } else {
      logger.info(s"Algorithm ${algoDef.code} is local and not predictive")
      OTHER_ALGORITHM
    }
  }

  private def splitExperimentJobByAlgorithm
    : Flow[LocalExperimentJob[F], AlgorithmInExperimentPreparation[F], NotUsed] =
    Flow[LocalExperimentJob[F]]
      .map { job =>
        val algorithms           = job.job.queryAlgorithms
        val validations          = job.job.query.validations
        val featuresTableService = job.featuresTableService
        val splitters            = job.splitters

        algorithms.map {
          case (algoSpec, algoDefn) =>
            AlgorithmInExperimentPreparation[F](job.job,
                                                algoSpec,
                                                algoDefn,
                                                validations,
                                                featuresTableService,
                                                splitters)
        }
      }
      .mapConcat(identity)
      .named("split-job")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def prepareAlgorithmFlow
    : Flow[AlgorithmInExperimentPreparation[F], AlgorithmInExperimentJob[F], NotUsed] =
    Flow[AlgorithmInExperimentPreparation[F]]
      .map { a =>
        val job                 = a.job
        val jobId               = UUID.randomUUID().toString
        val query               = job.query
        val algorithmSpec       = a.algorithmSpec
        val algorithmDefinition = a.algorithmDefinition
        val featuresQuery       = query.features(job.inputTable, None)
        val subJob =
          AlgorithmWithCVFlow.Job(jobId,
                                  algorithmSpec,
                                  algorithmDefinition,
                                  a.featuresTableService,
                                  featuresQuery,
                                  a.splitters,
                                  job.metadata)
        logger.info(s"Prepared mining query sub job $subJob")
        AlgorithmInExperimentJob(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  /**
    * Run a local algorithm, benchmark the results using cross-validation and return the results
    */
  private def runLocalAlgorithmWithCrossValidation
    : Flow[AlgorithmInExperimentJob[F], AlgorithmInExperimentResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[AlgorithmInExperimentJob[F]](outputPorts = 3))
        val runAlgorithmAndValidate =
          algorithmWithCVFlow.runLocalAlgorithmAndCrossValidate(parallelism = 10)
        val zip = builder.add(ZipWith(AlgorithmInExperimentResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithmAndValidate
          .map(r => r.model.fold(identity, identity)) ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-cross-validation")

  /**
    * Run a local algorithm and return its results
    */
  private def runLocalAlgorithm
    : Flow[AlgorithmInExperimentJob[F], AlgorithmInExperimentResult, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast    = builder.add(Broadcast[AlgorithmInExperimentJob[F]](outputPorts = 3))
        val runAlgorithm = algorithmWithCVFlow.runAlgorithmOnLocalData
        val takeModel    = Flow[AlgorithmResults].map(extractResult)
        val zip          = builder.add(ZipWith(AlgorithmInExperimentResult))

        broadcast.out(0).map(_.job) ~> zip.in0
        broadcast.out(1).map(_.algorithmSpec) ~> zip.in1
        broadcast.out(2).map(_.subJob) ~> runAlgorithm.map(_._2) ~> takeModel ~> zip.in2

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-only")

  private def extractResult(response: AlgorithmResults): JobResult = {
    val algorithm = response.job.algorithmSpec
    logger.whenDebugEnabled(
      logger.debug(s"Extract result from response: ${response.results}")
    )
    response.results.headOption match {
      case Some(model) => model
      case None =>
        ErrorJobResult(Some(response.job.jobId),
                       algorithmExecutor.node,
                       OffsetDateTime.now(),
                       Some(algorithm.code),
                       "No results")
    }
  }

}
