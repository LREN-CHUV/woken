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

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.{ Done, NotUsed }
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.http.scaladsl.model.ws.{ Message, TextMessage, WebSocketRequest }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }

import scala.collection.immutable.Seq
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import spray.json._
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.messages.remoting.RemoteLocation

object WebSocketClient extends SprayJsonSupport with PredefinedToResponseMarshallers {

  def sendReceive(location: RemoteLocation, query: MiningQuery)(
      implicit actorSystem: ActorSystem,
      materializer: Materializer
  ): Future[(RemoteLocation, QueryResult)] = {
    val promise: Promise[(RemoteLocation, QueryResult)] = Promise[(RemoteLocation, QueryResult)]()
    val q: String                                       = query.toJson.compactPrint
    sendReceive(location, q, promise)
  }

  def sendReceive(location: RemoteLocation, query: ExperimentQuery)(
      implicit actorSystem: ActorSystem,
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
      materializer: Materializer
  ): Future[(RemoteLocation, QueryResult)] = {
    val sink: Sink[Message, Future[Done]] =
      Sink.foreach {
        case message: TextMessage.Strict =>
          val queryResult: QueryResult = message.text.parseJson.convertTo[QueryResult]
          promise.completeWith(Future.successful((location, queryResult)))
        case err =>
          promise.failure(new RuntimeException(s"Response type format is not supported: $err"))
      }

    val source: Source[Message, NotUsed] = Source.single(TextMessage.Strict(query))
    val flow: Flow[Message, Message, (Future[Done], NotUsed)] =
      Flow
        .fromSinkAndSourceMat(sink, source)(Keep.both)
        .keepAlive(FiniteDuration(1, TimeUnit.SECONDS), () => TextMessage.apply("heart beat"))

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
