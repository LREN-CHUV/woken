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
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.http.scaladsl.model.{ HttpRequest, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import eu.hbp.mip.woken.backends.HttpClient
import eu.hbp.mip.woken.config.RemoteLocation
import eu.hbp.mip.woken.core.model.Shapes
import eu.hbp.mip.woken.messages.external.{ ExternalAPIProtocol, QueryResult }

import scala.concurrent.{ ExecutionContext, Future }
import cats.data._
import cats.implicits._
import spray.json._
import ExternalAPIProtocol._

case class WokenService(node: String)(implicit val system: ActorSystem,
                                      implicit val materializer: ActorMaterializer)
    extends DefaultJsonProtocol
    with SprayJsonSupport {

  implicit private val ec: ExecutionContext = system.dispatcher

  // TODO: keep outgoing connections flows
  // val connectionFlow: Flow[HttpRequest, HttpResponse, _] = Http().outgoingConnection("host.com")
  // def pathToRequest(path : String) = HttpRequest(uri=Uri.Empty.withPath(Path(path)))
  // val reqFlow = Flow[String] map pathToRequest
  // see https://stackoverflow.com/questions/37659421/what-is-the-best-way-to-combine-akka-http-flow-in-a-scala-stream-flow?rq=1

  def queryFlow: Flow[RemoteLocation, (RemoteLocation, QueryResult), NotUsed] =
    httpQueryFlow
  // TODO: case remoteLocation.url startsWith "http" => httpQueryFlow
  // TODO: case remoteLocation.url startsWith "ws" => webserviceQueryFlow
  // TODO: case remoteLocation.url startsWith "akka" => actorQueryFlow

  def httpQueryFlow: Flow[RemoteLocation, (RemoteLocation, QueryResult), NotUsed] =
    Flow[RemoteLocation]
      .map(location => HttpClient.sendReceive(Get(location)).map((location, _)))
      .mapAsync(4)(identity)
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

  private def Get(location: RemoteLocation): HttpRequest =
    location.credentials.foldLeft(HttpClient.Get(location.url))(
      (get, creds) => get.addHeader(Authorization(BasicHttpCredentials(creds.user, creds.password)))
    )

}
