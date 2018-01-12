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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import eu.hbp.mip.woken.backends.chronos.{ChronosJob, ChronosJobLiveliness}
import eu.hbp.mip.woken.messages.external.MiningQuery
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContextExecutor, Future}

object HttpClient extends DefaultJsonProtocol with SprayJsonSupport {

  def sendReceive(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] =
    Http().singleRequest(request)

  def Delete(url: String): HttpRequest = HttpRequest(
    method = HttpMethods.DELETE,
    uri = url
  )

  def Get(url: String): HttpRequest = Get(Uri(url))

  def Get(url: Uri): HttpRequest =
    HttpRequest(
      method = HttpMethods.GET,
      uri = url
    ).addHeader(Host(url.authority.host.address(), url.authority.port))

  def Post(url: String, job: ChronosJob)(implicit actorSystem: ActorSystem): Future[HttpRequest] =
    Post(Uri(url), job)

  def Post(url: Uri, job: ChronosJob)(implicit actorSystem: ActorSystem): Future[HttpRequest] = {
    import ChronosJob._
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(job).to[RequestEntity].map { entity =>
      HttpRequest(
        method = HttpMethods.POST,
        uri = url,
        entity = entity
      )

    }
  }

  def Post(url: String, job: DockerJob)(implicit actorSystem: ActorSystem): Future[HttpRequest] =
    Post(Uri(url), job)

  def Post(url: Uri, job: DockerJob)(implicit actorSystem: ActorSystem): Future[HttpRequest] = {
    import DockerJob._
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(job).to[RequestEntity].map { entity =>
      HttpRequest(
        method = HttpMethods.POST,
        uri = url,
        entity = entity
      )

    }
  }

  def Post(url: String, query: MiningQuery)(implicit actorSystem: ActorSystem): Future[HttpRequest] =
    Post(Uri(url), query)

  def Post(url: Uri, query: MiningQuery)(implicit actorSystem: ActorSystem): Future[HttpRequest] = {
    import DockerJob._
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(query).to[RequestEntity].map { entity =>
      HttpRequest(
        method = HttpMethods.POST,
        uri = url,
        entity = entity
      )

    }
  }

  def unmarshalChronosResponse(
      entity: HttpEntity
  )(implicit actorSystem: ActorSystem): Future[List[ChronosJobLiveliness]] = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    import ChronosJobLiveliness._
    Unmarshal(entity).to[List[ChronosJobLiveliness]]
  }

}
