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

package eu.hbp.mip.woken.api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import eu.hbp.mip.woken.config.{ AppConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.AlgorithmLibraryService
import eu.hbp.mip.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult, queryProtocol }
import eu.hbp.mip.woken.core.features.Queries._

import scala.concurrent.{ ExecutionContext, Future }
import spray.json._
import queryProtocol._
import akka.stream.{ ActorAttributes, Supervision }
import com.typesafe.scalalogging.LazyLogging
import eu.hbp.mip.woken.api.flows.ExperimentFlowHandler

import scala.util.{ Failure, Success, Try }

trait WebsocketSupport extends LazyLogging {

  val masterRouter: ActorRef
  val experimentFlowHandler: ExperimentFlowHandler
  val featuresDatabase: FeaturesDAL
  val appConfiguration: AppConfiguration
  val jobsConf: JobsConfiguration
  implicit val timeout: Timeout
  implicit val executionContext: ExecutionContext

  private val decider: Supervision.Decider = {
    case err: Exception =>
      logger.error(err.getMessage, err)
      Supervision.Resume
    case otherErr =>
      logger.error("Unknown error. Stopping the stream.", otherErr)
      Supervision.Stop
  }

  def listMethodsFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case tm: TextMessage =>
          AlgorithmLibraryService().algorithms()
      }
      .map { result =>
        TextMessage(result.compactPrint)
      }

  def experimentFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(jsonEncodedString) =>
          Try {
            jsonEncodedString.parseJson.convertTo[ExperimentQuery]
          }
      }
      .filter {
        case Success(_) => true
        case Failure(err) =>
          logger.error("Deserialize failed", err)
          false

      }
      .map(_.get)
      .via(experimentFlowHandler.experimentFlow)
      .map { result =>
        TextMessage(result.toJson.compactPrint)
      }
      .named("Experiment WS flow")

  def miningFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case tm: TextMessage =>
          val jsonEncodeStringMsg = tm.getStrictText
          Try {
            jsonEncodeStringMsg.parseJson.convertTo[MiningQuery]
          }
      }
      .filter {
        case Success(_) => true
        case Failure(err) =>
          logger.error("Deserialize failed", err)
          false

      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .filter(_.isSuccess)
      .map(_.get)
      .mapAsync(1) { miningQuery: MiningQuery =>
        if (miningQuery.algorithm.code.isEmpty || miningQuery.algorithm.code == "data") {
          Future.successful(
            featuresDatabase.queryData(jobsConf.featuresTable, miningQuery.dbAllVars)
          )
        } else {
          val result = (masterRouter ? miningQuery).mapTo[QueryResult]
          result.map(_.toJson)
        }
      }
      .map { result =>
        TextMessage(result.compactPrint)
      }
      .named("Mining WS flow.")
}
