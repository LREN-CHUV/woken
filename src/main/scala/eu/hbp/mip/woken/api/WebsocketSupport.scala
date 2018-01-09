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
import akka.cluster.client.ClusterClient
import akka.pattern.ask
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import eu.hbp.mip.woken.config.{ AppConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.AlgorithmLibraryService
import eu.hbp.mip.woken.messages.external.{
  ExperimentQuery,
  ExternalAPIProtocol,
  MiningQuery,
  QueryResult
}
import scala.concurrent.{ ExecutionContext, Future }

import spray.json._
import ExternalAPIProtocol._

trait WebsocketSupport {

  val featuresDatabase: FeaturesDAL
  val appConfiguration: AppConfiguration
  val jobsConf: JobsConfiguration
  val cluster: ActorRef
  val entryPoint: String
  implicit val timeout: Timeout
  implicit val executionContext: ExecutionContext

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
          jsonEncodedString.parseJson.convertTo[ExperimentQuery]
      }
      .mapAsync(1) { query =>
        (cluster ? ClusterClient.Send(entryPoint, query, localAffinity = true))
          .mapTo[QueryResult]
      }
      .map { result =>
        TextMessage(result.toJson.compactPrint)
      }

  def miningFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case tm: TextMessage =>
          val jsonEncodeStringMsg = tm.getStrictText
          jsonEncodeStringMsg.parseJson.convertTo[MiningQuery]
      }
      .mapAsync(1) { minQuery: MiningQuery =>
        if (minQuery.algorithm.code.isEmpty || minQuery.algorithm.code == "data") {
          Future.successful(featuresDatabase.queryData(jobsConf.featuresTable, {
            minQuery.variables ++ minQuery.covariables ++ minQuery.grouping
          }.distinct.map(_.code)))
        } else {
          val result = (cluster ? ClusterClient.Send(entryPoint, minQuery, localAffinity = true))
            .mapTo[QueryResult]
          result.map(_.toJson)
        }
      }
      .map { result =>
        TextMessage(result.compactPrint)
      }
}
