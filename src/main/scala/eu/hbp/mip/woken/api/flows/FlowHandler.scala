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

import akka.stream.FlowShape
import akka.stream.scaladsl.{ Flow, GraphDSL, Merge, Partition }
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.core.CoordinatorConfig
import eu.hbp.mip.woken.core.model.Shapes
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.messages.query.{ MiningQuery, QueryResult, queryProtocol }

trait FlowHandler {

  type ValidatedMiningJob = (DockerJob, MiningQuery)

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
  protected def conditionalFlow[IN, OUT](f: IN => Boolean,
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

  /**
    * Validation flow
    *
    * @param f           - validation function
    * @param successFlow - success flow
    * @param errorFlow   - error flow
    * @tparam IN  - input type
    * @tparam OUT - output type
    * @tparam T   - job type
    * @return
    */
  protected def validationFlow[IN, OUT, T](
      f: IN => Validation[T],
      successFlow: Flow[Validation[T], OUT, _],
      errorFlow: Flow[Validation[T], OUT, _]
  ): Flow[IN, OUT, Any] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      def partitionFunction = (a: IN) => f(a).fold(_ => 0, _ => 1)

      val partitioner = builder.add(Partition[IN](2, partitionFunction))
      val merger      = builder.add(Merge[OUT](2))
      partitioner.out(0).map(f).via(successFlow) ~> merger
      partitioner.out(1).map(f).via(errorFlow) ~> merger

      FlowShape(partitioner.in, merger.out)
    })

  protected def compoundResult(coordinatorConfig: CoordinatorConfig,
                               queryResults: List[QueryResult]): QueryResult = {
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
