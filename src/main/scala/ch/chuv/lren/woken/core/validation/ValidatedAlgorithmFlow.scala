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

package ch.chuv.lren.woken.core.validation

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorContext
import akka.event.Logging
import akka.stream.{ FlowShape, Materializer }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Zip }
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.CoordinatorActor
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, JobResult, PfaJobResult }
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, MiningQuery, ValidationSpec }
import ch.chuv.lren.woken.messages.validation.{ Score, validationProtocol }
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import spray.json._
import validationProtocol._

import scala.concurrent.ExecutionContext

object ValidatedAlgorithmFlow {

  case class Job(jobId: String,
                 inputDb: String,
                 inputTable: String,
                 query: MiningQuery,
                 metadata: List[VariableMetaData],
                 validations: List[ValidationSpec],
                 algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)

    if (!algorithmDefinition.predictive) {
      assert(validations.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, Score]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: JobResult)

}

case class ValidatedAlgorithmFlow(
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  import ValidatedAlgorithmFlow._

  private val log = Logging(context.system, getClass)

  private val crossValidationFlow = CrossValidationFlow(executeJobAsync, featuresDatabase, context)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def runAlgorithmAndValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ResultResponse, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val broadcast = builder.add(Broadcast[ValidatedAlgorithmFlow.Job](2))
        val zip       = builder.add(Zip[CoordinatorActor.Response, ValidationResults]())
        val response  = builder.add(buildResponse)

        // connect the graph
        broadcast.out(0) ~> runAlgoInDocker ~> zip.in0
        broadcast.out(1) ~> crossValidate(parallelism) ~> zip.in1
        zip.out ~> response

        FlowShape(broadcast.in, response.out)
      })
      .named("run-algorithm-and-validate")

  def runAlgoInDocker: Flow[ValidatedAlgorithmFlow.Job, CoordinatorActor.Response, NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .mapAsync(1) { job =>
        val algorithm = job.query.algorithm

        log.info(s"Start job for algorithm ${algorithm.code}")

        // Spawn a CoordinatorActor
        val jobId = UUID.randomUUID().toString
        val featuresQuery =
          job.query.features(job.inputTable, !job.algorithmDefinition.supportsNullValues, None)
        val subJob =
          DockerJob(jobId,
                    job.algorithmDefinition.dockerImage,
                    job.inputDb,
                    featuresQuery,
                    job.query.algorithm,
                    job.metadata)
        executeJobAsync(subJob)
      }
      .named("learn-from-all-data")

  private def crossValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ValidationResults, NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .map { job =>
        job.validations.map { v =>
          val jobId = UUID.randomUUID().toString
          CrossValidationFlow.Job(jobId,
                                  job.inputDb,
                                  job.inputTable,
                                  job.query,
                                  job.metadata,
                                  v,
                                  job.algorithmDefinition)
        }
      }
      .mapConcat(identity)
      .via(crossValidationFlow.crossValidate(parallelism))
      .map(_.map(t => t._1.validation -> t._2))
      .fold[Map[ValidationSpec, Either[String, Score]]](Map()) { (m, rOpt) =>
        rOpt.fold(m) { r =>
          m + r
        }
      }
      .named("cross-validate")

  private def buildResponse
    : Flow[(CoordinatorActor.Response, ValidationResults), ResultResponse, NotUsed] =
    Flow[(CoordinatorActor.Response, ValidationResults)]
      .map {
        case (response, validations) =>
          val validationsJson = JsArray(
            validations
              .map({
                case (key, Right(value)) =>
                  JsObject("code" -> JsString(key.code), "data" -> value.toJson)
                case (key, Left(message)) =>
                  JsObject("code" -> JsString(key.code), "error" -> JsString(message))
              })
              .toVector
          )

          val algorithm = response.job.algorithmSpec
          response.results.headOption match {
            case Some(pfa: PfaJobResult) =>
              val model = pfa.injectCell("validations", validationsJson)
              ResultResponse(algorithm, model)
            case Some(model) =>
              ResultResponse(algorithm, model)
            case None =>
              ResultResponse(algorithm,
                             ErrorJobResult(response.job.jobId,
                                            node = "",
                                            OffsetDateTime.now(),
                                            algorithm.code,
                                            "No results"))
          }
      }
      .named("build-response")
}
