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

package eu.hbp.mip.woken.backends.woken

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorMaterializer, FlowShape }
import akka.stream.scaladsl._
import eu.hbp.mip.woken.backends.{ AkkaClusterClient, HttpClient, WebSocketClient }
import eu.hbp.mip.woken.config.RemoteLocation
import eu.hbp.mip.woken.core.model.Shapes
import eu.hbp.mip.woken.messages.external.{ QueryResult, _ }

import scala.concurrent.{ ExecutionContext, Future }
import com.typesafe.scalalogging.LazyLogging
import cats.data._
import cats.implicits._
import spray.json._
import ExternalAPIProtocol._
import HttpClient._

case class WokenService(node: String)(implicit val system: ActorSystem,
                                      implicit val materializer: ActorMaterializer)
    extends DefaultJsonProtocol
    with SprayJsonSupport
    with LazyLogging {

  implicit private val ec: ExecutionContext = system.dispatcher

  // TODO: keep outgoing connections flows
  // val connectionFlow: Flow[HttpRequest, HttpResponse, _] = Http().outgoingConnection("host.com")
  // def pathToRequest(path : String) = HttpRequest(uri=Uri.Empty.withPath(Path(path)))
  // val reqFlow = Flow[String] map pathToRequest
  // see https://stackoverflow.com/questions/37659421/what-is-the-best-way-to-combine-akka-http-flow-in-a-scala-stream-flow?rq=1

  def queryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      def switcher(locationAndQuery: (RemoteLocation, Query)): Int =
        locationAndQuery._1.url.scheme match {
          case "http" => 0
          case "ws"   => 1
          case "akka" => 2
          case _      => 1
        }

      val partition = builder.add(Partition[(RemoteLocation, Query)](3, switcher))
      val merger    = builder.add(Merge[(RemoteLocation, QueryResult)](3))

      partition.out(0).via(httpQueryFlow) ~> merger
      partition.out(1).via(wsQueryFlow) ~> merger
      partition.out(2).via(actorQueryFlow) ~> merger

      FlowShape(partition.in, merger.out)
    })

  def httpQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .map {
        case (location, query: MiningQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          Post(location, query).map((location, _))
        case (location, query: ExperimentQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          Post(location, query).map((location, _))
      }
      .mapAsync(100)(identity)
      .mapAsync(1) {
        case (url, response) if response.status.isSuccess() =>
          (url.pure[Future], Unmarshal(response).to[QueryResult]).mapN((_, _))
        case (url, response) =>
          (url,
           QueryResult("",
                       node,
                       OffsetDateTime.now(),
                       Shapes.error.mime,
                       "dispatch",
                       None,
                       Some(response.entity.toString))).pure[Future]
      }
      .map(identity)

  def wsQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .mapAsync(100) {
        case (location, query: MiningQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          WebSocketClient.sendReceive(location, query)
        case (location, query: ExperimentQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          WebSocketClient.sendReceive(location, query)
      }

  def actorQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .mapAsync(100) {
        case (location, query) => AkkaClusterClient.sendReceive(location, query).map((location, _))
      }

}
