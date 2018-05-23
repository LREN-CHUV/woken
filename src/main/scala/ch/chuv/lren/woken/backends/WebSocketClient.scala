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

package ch.chuv.lren.woken.backends

import akka.actor.ActorSystem
import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.http.scaladsl.model.ws.{ Message, TextMessage, WebSocketRequest }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.NotUsed
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }
import spray.json._
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import com.typesafe.scalalogging.LazyLogging

object WebSocketClient
    extends SprayJsonSupport
    with PredefinedToResponseMarshallers
    with LazyLogging {

  def sendReceive(location: RemoteLocation, query: MiningQuery)(
      implicit actorSystem: ActorSystem,
      executionContext: ExecutionContext,
      materializer: Materializer
  ): Future[(RemoteLocation, QueryResult)] = {
    val promise: Promise[(RemoteLocation, QueryResult)] = Promise[(RemoteLocation, QueryResult)]()
    val q: String                                       = query.toJson.compactPrint
    sendReceive(location, q, promise)
  }

  def sendReceive(location: RemoteLocation, query: ExperimentQuery)(
      implicit actorSystem: ActorSystem,
      executionContext: ExecutionContext,
      materializer: Materializer
  ): Future[(RemoteLocation, QueryResult)] = {
    val promise: Promise[(RemoteLocation, QueryResult)] = Promise[(RemoteLocation, QueryResult)]()
    val q: String                                       = query.toJson.compactPrint
    sendReceive(location, q, promise)
  }

  private def sendReceive(location: RemoteLocation,
                          query: String,
                          promise: Promise[(RemoteLocation, QueryResult)])(
      implicit actorSystem: ActorSystem,
      executionContext: ExecutionContext,
      materializer: Materializer
  ): Future[(RemoteLocation, QueryResult)] = {
    val sink: Sink[Message, NotUsed] =
      Flow[Message]
        .collect {
          case TextMessage.Strict(jsonEncodedString) => Future(jsonEncodedString)
          case TextMessage.Streamed(stream)          => stream.runFold("")(_ + _)
          case err =>
            val e = new RuntimeException(s"Response type format is not supported: $err")
            promise.failure(e)
            throw e
        }
        .mapAsync(3)(identity)
        .map { jsonEncodedString =>
          Try {
            jsonEncodedString.parseJson.convertTo[QueryResult]
          }
        }
        .to(Sink.foreach {
          case Success(queryResult) =>
            promise.completeWith(Future.successful((location, queryResult)))
          case Failure(err) =>
            logger.error("Deserialize failed", err)
            promise.failure(new RuntimeException(s"Response type format is not supported: $err"))
        })

    val source: Source[Message, Promise[Option[Message]]] =
      Source.single(TextMessage.Strict(query)).concatMat(Source.maybe[Message])(Keep.right)
    val flow: Flow[Message, Message, NotUsed] =
      Flow
        .fromSinkAndSourceMat(sink, source)(Keep.left)

    val (upgradeResponse, closed) =
      Http().singleWebSocketRequest(
        WebSocketRequest(
          location.url,
          extraHeaders = location.credentials
            .map(cred => Seq(Authorization(BasicHttpCredentials(cred.user, cred.password))))
            .getOrElse(Seq.empty)
        ),
        flow
      )

    import actorSystem.dispatcher

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Done
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(_ => println("completed."))

    promise.future

  }

}
