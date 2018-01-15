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

package eu.hbp.mip.woken.backends

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
import eu.hbp.mip.woken.config.RemoteLocation
import eu.hbp.mip.woken.messages.external.{ ExperimentQuery, MiningQuery, QueryResult }

import scala.collection.immutable.Seq
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import spray.json._
import eu.hbp.mip.woken.messages.external.ExternalAPIProtocol._

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
