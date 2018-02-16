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

package eu.hbp.mip.woken.core.validation

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorContext
import akka.event.Logging
import akka.stream.{ FlowShape, Materializer }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Zip }
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.AlgorithmDefinition
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig }
import eu.hbp.mip.woken.core.model.{ ErrorJobResult, JobResult, PfaJobResult }
import eu.hbp.mip.woken.core.features.Queries._
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, MiningQuery, ValidationSpec }
import spray.json._

import scala.concurrent.ExecutionContext

object ValidatedAlgorithmFlow {

  case class Job(jobId: String,
                 inputDb: String,
                 inputTable: String,
                 query: MiningQuery,
                 metadata: JsObject,
                 validations: List[ValidationSpec],
                 algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)

    if (!algorithmDefinition.predictive) {
      assert(validations.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, JsObject]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: JobResult)

}

case class ValidatedAlgorithmFlow(
    coordinatorConfig: CoordinatorConfig,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  import ValidatedAlgorithmFlow._

  private val log = Logging(context.system, getClass)

  private val crossValidationFlow = CrossValidationFlow(coordinatorConfig, context)

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
        CoordinatorActor.future(subJob, coordinatorConfig, context)
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
      .map(t => t._1.validation -> Right(t._2))
      .fold[Map[ValidationSpec, Either[String, JsObject]]](Map()) { (m, r) =>
        m + r
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
                  JsObject("code" -> JsString(key.code), "data" -> value)
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
