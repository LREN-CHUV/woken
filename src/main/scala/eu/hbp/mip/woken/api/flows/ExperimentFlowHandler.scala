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

package eu.hbp.mip.woken.api.flows

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream.{ FlowShape, Materializer }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Keep, Merge, Partition, Zip }
import eu.hbp.mip.woken.config.{ AlgorithmDefinition, AppConfiguration }
import eu.hbp.mip.woken.core.ExperimentActor.{ Job, Response }
import eu.hbp.mip.woken.core.{ CoordinatorConfig, ExperimentActor, ExperimentFlow }
import eu.hbp.mip.woken.core.model.{ ErrorJobResult, JobResult, PfaExperimentJobResult, Shapes }
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.messages.query.{
  AlgorithmSpec,
  ExperimentQuery,
  QueryResult,
  queryProtocol
}
import eu.hbp.mip.woken.service.DispatcherService
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/**
  * Experiment Flow.
  */
class ExperimentFlowHandler(
    appConfiguration: AppConfiguration,
    experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job],
    dispatcherService: DispatcherService,
    coordinatorConfig: CoordinatorConfig,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  lazy val experimentFlow: Flow[Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    ExperimentFlow(coordinatorConfig, algorithmLookup, context).flow

  private def canBuildValidJob(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    experimentQuery2JobF(query)

  private val validationFailedFlow: Flow[Validation[ExperimentActor.Job], QueryResult, _] =
    Flow[Validation[ExperimentActor.Job]].map { errorMsg =>
      ErrorJobResult("",
                     "",
                     OffsetDateTime.now(),
                     "experiment",
                     errorMsg.toEither.left.get.reduceLeft(_ + ", " + _)).asQueryResult
    }

  private def isNewJobRequired(job: ExperimentActor.Job): Boolean =
    dispatcherService.dispatchTo(job.query.trainingDatasets) match {
      case (_, true) => true
      case _         => false
    }

  private val executionFlow: Flow[ExperimentActor.Job, QueryResult, NotUsed] =
    Flow[ExperimentActor.Job]
      .map { job =>
        job.query
      }
      .via(dispatcherService.remoteDispatchExperimentFlow())
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }
      .map {
        case List() =>
          ErrorJobResult("",
                         coordinatorConfig.jobsConf.node,
                         OffsetDateTime.now(),
                         "experiment",
                         "No results").asQueryResult

        case List(result) => result

        case listOfResults =>
          compoundResult(listOfResults)
      }

  private val zipper = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val bcast  = builder.add(Broadcast[ExperimentActor.Job](2))
    val merger = builder.add(Zip[ExperimentActor.Job, Map[AlgorithmSpec, JobResult]])
    bcast.out(0).via(experimentFlow) ~> merger.in1
    bcast.out(1) ~> merger.in0
    FlowShape(bcast.in, merger.out)
  })

  private val startExperimentTask: Flow[ExperimentActor.Job, QueryResult, NotUsed] =
    Flow[ExperimentActor.Job].via(zipper).map {
      case (job, results) =>
        val algorithms = job.query.algorithms
        log.info("Experiment - build final response")
        log.info(s"Algorithms: $algorithms")
        log.info(s"Results: $results")

        assert(results.size == algorithms.size, "There should be as many results as algorithms")
        assert(results.keySet equals algorithms.toSet,
               "Algorithms defined in the results should match the incoming list of algorithms")

        val pfa = PfaExperimentJobResult(experimentJobId = job.jobId,
                                         experimentNode = coordinatorConfig.jobsConf.node,
                                         results = results)

        pfa.asQueryResult
    }

  private val shouldStartANewJob: Flow[Validation[ExperimentActor.Job], QueryResult, NotUsed] =
    Flow[Validation[ExperimentActor.Job]]
      .map(job => job.toOption.get)
      .via(conditionalFlow(isNewJobRequired, startExperimentTask, executionFlow))

  val experimentFlowRouter: Flow[ExperimentQuery, QueryResult, _] =
    Flow[ExperimentQuery]
      .via(
        validationFlow(
          canBuildValidJob,
          validationFailedFlow,
          shouldStartANewJob
        )
      )

  /**
    * Build a conditional flow.
    *
    * @param f         - conditional function
    * @param trueFlow  - flow that will be executed in case of evaluation of function f is true
    * @param falseFlow - flow that will be executed in case of evaluation of function f is false
    * @tparam IN  - input type
    * @tparam OUT - output type
    * @return a flow instance.
    */
  private def conditionalFlow[IN, OUT](f: IN => Boolean,
                                       trueFlow: Flow[IN, OUT, _],
                                       falseFlow: Flow[IN, OUT, _]): Flow[IN, OUT, Any] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      def partitionFunction = (a: IN) => if (f(a)) 0 else 1

      val partitioner = builder.add(Partition[IN](2, partitionFunction))
      val merger      = builder.add(Merge[OUT](2))

      partitioner.out(0).via(trueFlow) ~> merger
      partitioner.out(1).via(falseFlow) ~> merger

      FlowShape(partitioner.in, merger.out)
    })

  private def validationFlow(
      f: ExperimentQuery => Validation[ExperimentActor.Job],
      successFlow: Flow[Validation[ExperimentActor.Job], QueryResult, _],
      errorFlow: Flow[Validation[ExperimentActor.Job], QueryResult, _]
  ): Flow[ExperimentQuery, QueryResult, Any] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      def partitionFunction = (a: ExperimentQuery) => f(a).fold(_ => 0, _ => 1)

      val partitioner = builder.add(Partition[ExperimentQuery](2, partitionFunction))
      val merger      = builder.add(Merge[QueryResult](2))
      partitioner.out(0).map(f).via(successFlow) ~> merger
      partitioner.out(1).map(f).via(errorFlow) ~> merger

      FlowShape(partitioner.in, merger.out)
    })

  private def compoundResult(queryResults: List[QueryResult]): QueryResult = {
    import spray.json._
    import queryProtocol._

    QueryResult(
      jobId = "",
      node = coordinatorConfig.jobsConf.node,
      timestamp = OffsetDateTime.now(),
      shape = Shapes.compound.mime,
      algorithm = "compound",
      data = Some(queryResults.toJson),
      error = None
    )
  }

}
