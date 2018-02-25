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

package ch.chuv.lren.woken.api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.service.AlgorithmLibraryService
import ch.chuv.lren.woken.messages.query.{
  ExperimentQuery,
  MiningQuery,
  QueryResult,
  queryProtocol
}
import ch.chuv.lren.woken.core.features.Queries._

import scala.concurrent.{ ExecutionContext, Future }
import spray.json._
import queryProtocol._
import akka.stream.{ ActorAttributes, Supervision }
import com.typesafe.scalalogging.LazyLogging

import scala.util.{ Failure, Success, Try }

trait WebsocketSupport extends LazyLogging {

  val masterRouter: ActorRef
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
          logger.error("Deserilize failed", err)
          false

      }
      .mapAsync(1) { query =>
        (masterRouter ? query.get).mapTo[QueryResult]
      }
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
