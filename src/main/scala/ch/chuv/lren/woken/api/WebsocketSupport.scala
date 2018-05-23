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
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.service.AlgorithmLibraryService
import ch.chuv.lren.woken.messages.query.{
  ExperimentQuery,
  MiningQuery,
  QueryResult,
  queryProtocol
}
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import spray.json._
import queryProtocol._
import akka.stream.{ ActorAttributes, Supervision }
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse }
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}
import com.typesafe.scalalogging.LazyLogging

trait WebsocketSupport {
  this: LazyLogging =>

  val masterRouter: ActorRef
  val appConfiguration: AppConfiguration
  val jobsConf: JobsConfiguration
  implicit val timeout: Timeout
  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

  private val decider: Supervision.Decider = {
    case err: Exception =>
      logger.error(err.getMessage, err)
      Supervision.Resume
    case otherErr =>
      logger.error("Unknown error. Stopping the stream.", otherErr)
      Supervision.Stop
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def listAlgorithmsFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case _: TextMessage =>
          AlgorithmLibraryService().algorithms
      }
      .map { result =>
        val serializedResult = result.compactPrint
        logger.debug(s"Return response for list of algorithms: $serializedResult")
        TextMessage(serializedResult)
      }
      .log("Algorithms")
      .named("List algorithms WebSocket flow")

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def listDatasetsFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict("") =>
          DatasetsQuery(None)
        case TextMessage.Strict(table) =>
          DatasetsQuery(Some(table))
      }
      .mapAsync(1) { query =>
        (masterRouter ? query).mapTo[DatasetsResponse]
      }
      .map { result =>
        val serializedResult = result.toJson.compactPrint
        logger.debug(s"Return response for list of datasets: $serializedResult")
        TextMessage(serializedResult)
      }
      .log("Datasets")
      .named("List datasets WebSocket flow")

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"))
  def listVariableMetadataFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(jsonEncodedString) =>
          Try {
            jsonEncodedString.parseJson.convertTo[VariablesForDatasetsQuery]
          }
      }
      .filter {
        case Success(_) => true
        case Failure(err) =>
          logger.error("Deserialize failed", err)
          false
      }
      .mapAsync(1) {
        case Success(query) =>
          (masterRouter ? query).mapTo[VariablesForDatasetsResponse]
        case Failure(e) =>
          throw e
      }
      .map { result =>
        val serializedResult = result.toJson.compactPrint
        logger.debug(s"Return response for list of variables: $serializedResult")
        TextMessage(serializedResult)
      }
      .log("Variables")
      .named("List variables")

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"))
  def experimentFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(jsonEncodedString) => Future(jsonEncodedString)
        case TextMessage.Streamed(stream)          => stream.runFold("")(_ + _)
      }
      .mapAsync(3)(identity)
      .map { jsonEncodedString =>
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
      .mapAsync(3) {
        case Success(query) =>
          (masterRouter ? query).mapTo[QueryResult]
        case Failure(e) =>
          throw e
      }
      .map { result: QueryResult =>
        val serializedResult = result.toJson.compactPrint
        logger.debug(s"Return response for experiment: $serializedResult")
        TextMessage(serializedResult)
      }
      .log("Result of experiment")
      .named("Experiment WebSocket flow")

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"))
  def miningFlow: Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(jsonEncodedString) => Future(jsonEncodedString)
        case TextMessage.Streamed(stream)          => stream.runFold("")(_ + _)
      }
      .mapAsync(3)(identity)
      .map { jsonEncodedString =>
        Try {
          jsonEncodedString.parseJson.convertTo[MiningQuery]
        }
      }
      .filter {
        case Success(_) => true
        case Failure(err) =>
          logger.error("Deserialize failed", err)
          false

      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .mapAsync(3) {
        case Success(query) =>
          (masterRouter ? query).mapTo[QueryResult]
        case Failure(e) =>
          throw e
      }
      .map { result: QueryResult =>
        val serializedResult = result.toJson.compactPrint
        logger.debug(s"Return response for mining: $serializedResult")
        TextMessage(serializedResult)
      }
      .log("Result of mining")
      .named("Mining WebSocket flow")
}
