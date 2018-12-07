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

package ch.chuv.lren.woken.validation.flows

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Zip }
import cats.effect.Effect
import ch.chuv.lren.woken.config.JobsConfiguration
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.mining.CoordinatorActor
import ch.chuv.lren.woken.service.FeaturesTableService
import ch.chuv.lren.woken.validation.FeaturesSplitter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object AlgorithmWithCVFlow {

  case class Job[F[_]](jobId: String,
                       inputTable: TableId,
                       query: MiningQuery,
                       cvSplitters: List[FeaturesSplitter[F]],
                       metadata: List[VariableMetaData],
                       algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)
    query.targetTable.foreach(t => assert(t == inputTable.name))

    if (!algorithmDefinition.predictive) {
      assert(cvSplitters.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, Score]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: Either[ErrorJobResult, PfaJobResult])

}

/**
  * Generates flows for execution of an algorithm that may require Cross Validation of the model built during its training phase.
  *
  * @tparam F Monadic effect
  */
case class AlgorithmWithCVFlow[F[_]: Effect](
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresTableService: FeaturesTableService[F],
    jobsConf: JobsConfiguration,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import AlgorithmWithCVFlow._

  private val crossValidationFlow =
    CrossValidationFlow(executeJobAsync, context)

  /**
    * Run a predictive and local algorithm and perform its validation procedure.
    *
    * If the algorithm is predictive, validate it using cross-validation for validation with local data.
    *
    * @param parallelism Parallelism factor
    * @return A flow that executes an algorithm and its validation procedures
    */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def runLocalAlgorithmAndValidate(
      parallelism: Int
  ): Flow[AlgorithmWithCVFlow.Job[F], ResultResponse, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val broadcast = builder.add(Broadcast[AlgorithmWithCVFlow.Job[F]](2))
        val zip       = builder.add(Zip[CoordinatorActor.Response, ValidationResults]())
        val response  = builder.add(buildResponse)

        // connect the graph
        broadcast.out(0) ~> runAlgorithmOnLocalData.map(_._2) ~> zip.in0
        broadcast.out(1) ~> crossValidate(parallelism) ~> zip.in1
        zip.out ~> response

        FlowShape(broadcast.in, response.out)
      })
      .named("run-algorithm-and-validate")

  /**
    * Execute an algorithm and learn from the local data.
    *
    * @return
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def runAlgorithmOnLocalData: Flow[AlgorithmWithCVFlow.Job[F],
                                    (AlgorithmWithCVFlow.Job[F], CoordinatorActor.Response),
                                    NotUsed] =
    Flow[AlgorithmWithCVFlow.Job[F]]
      .mapAsync(1) { job =>
        val algorithm = job.query.algorithm

        logger.info(s"Start job for algorithm ${algorithm.code}")

        // Spawn a CoordinatorActor
        val jobId         = UUID.randomUUID().toString
        val featuresQuery = job.query.features(job.inputTable, None)
        val subJob        = createDockerJob(job, jobId, featuresQuery)

        executeJobAsync(subJob).map(response => (job, response))
      }
      .log("Learned from available local data")
      .named("learn-from-available-local-data")

  private def createDockerJob(job: AlgorithmWithCVFlow.Job[F],
                              jobId: String,
                              featuresQuery: FeaturesQuery): DockerJob =
    DockerJob(jobId, featuresQuery, job.query.algorithm, job.algorithmDefinition, job.metadata)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def crossValidate(
      parallelism: Int
  ): Flow[AlgorithmWithCVFlow.Job[F], ValidationResults, NotUsed] =
    Flow[AlgorithmWithCVFlow.Job[F]]
      .map { job =>
        job.cvSplitters.map { splitter =>
          val jobId = UUID.randomUUID().toString
          val orderBy = featuresTableService.table.primaryKey match {
            case pk1 :: Nil => Some(pk1.name)
            case _          => None
          }
          val query = job.query.features(job.inputTable, orderBy)
          createCrossValidationJob(job, splitter, jobId, query)
        }
      }
      .mapConcat(identity)
      .via(crossValidationFlow.crossValidate(parallelism))
      .map(_.map(t => t._1.splitter.definition -> t._2))
      .fold[Map[ValidationSpec, Either[String, Score]]](Map()) { (m, rOpt) =>
        rOpt.fold(m) { r =>
          m + (r._1.validation -> r._2)
        }
      }
      .log("Cross validation results")
      .named("cross-validate")

  private def createCrossValidationJob(job: AlgorithmWithCVFlow.Job[F],
                                       splitter: FeaturesSplitter[F],
                                       jobId: String,
                                       query: FeaturesQuery) =
    CrossValidationFlow.Job(jobId,
                            query,
                            job.metadata,
                            splitter,
                            featuresTableService,
                            job.query.algorithm,
                            job.algorithmDefinition)

// TODO: keep?
  private def nodeOf(spec: ValidationSpec): Option[String] =
    spec.parameters.find(_.code == "node").map(_.value)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def buildResponse
    : Flow[(CoordinatorActor.Response, ValidationResults), ResultResponse, NotUsed] =
    Flow[(CoordinatorActor.Response, ValidationResults)]
      .map {
        case (response, validations) =>
          val algorithm = response.job.algorithmSpec
          response.results.headOption match {
            case Some(pfa: PfaJobResult) =>
              val model = pfa.copy(validations = pfa.validations ++ validations)
              ResultResponse(algorithm, Right(model))
            case Some(model) =>
              logger.warn(
                s"Expected a PfaJobResult, got $model. All results and validations are discarded"
              )
              val jobResult =
                ErrorJobResult(Some(response.job.jobId),
                               node = jobsConf.node,
                               OffsetDateTime.now(),
                               Some(algorithm.code),
                               s"Expected a PfaJobResult, got ${model.getClass.getName}")
              ResultResponse(algorithm, Left(jobResult))
            case None =>
              val jobResult = ErrorJobResult(Some(response.job.jobId),
                                             node = jobsConf.node,
                                             OffsetDateTime.now(),
                                             Some(algorithm.code),
                                             "No results")
              ResultResponse(algorithm, Left(jobResult))
          }
      }
      .log("Response")
      .named("build-response")
}
