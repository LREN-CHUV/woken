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
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials, Host }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import cats.Id
import cats.effect.Effect
import ch.chuv.lren.woken.backends.faas.chronos.ChronosJobLiveliness
import spray.json.DefaultJsonProtocol
import ch.chuv.lren.woken.backends.faas.chronos.ChronosJob
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import com.typesafe.scalalogging.LazyLogging
import sup.{ HealthCheck, mods }
import sup.modules.sttp._
import com.softwaremill.sttp.{ sttp => request, Uri => _, _ }

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.language.higherKinds

object HttpClient extends DefaultJsonProtocol with SprayJsonSupport with LazyLogging {

  def sendReceive(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] =
    Http().singleRequest(request)

  def Delete(url: String): HttpRequest = HttpRequest(
    method = HttpMethods.DELETE,
    uri = url
  )

  def Get(location: RemoteLocation): HttpRequest = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = location.url
    ).addHeader(Host(location.url.authority.host.address(), location.url.authority.port))

    location.credentials.foldLeft(request)(
      (r, creds) => r.addHeader(Authorization(BasicHttpCredentials(creds.user, creds.password)))
    )
  }

  def Get(url: String): HttpRequest = Get(Uri(url))

  def Get(url: Uri): HttpRequest =
    HttpRequest(
      method = HttpMethods.GET,
      uri = url
    ).addHeader(Host(url.authority.host.address(), url.authority.port))

  def Post(url: Uri, job: ChronosJob)(implicit actorSystem: ActorSystem): Future[HttpResponse] = {
    import ChronosJob._
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(job).to[RequestEntity].flatMap { entity =>
      Post(RemoteLocation(url, None), entity)
    }
  }

  def Post(location: RemoteLocation,
           query: MiningQuery)(implicit actorSystem: ActorSystem): Future[HttpResponse] = {
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(query).to[RequestEntity].flatMap { entity =>
      Post(location, entity)
    }
  }

  def Post(location: RemoteLocation,
           query: ExperimentQuery)(implicit actorSystem: ActorSystem): Future[HttpResponse] = {
    implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
    Marshal(query).to[RequestEntity].flatMap { entity =>
      Post(location, entity)
    }
  }

  private def Post(location: RemoteLocation, entity: RequestEntity)(
      implicit actorSystem: ActorSystem
  ): Future[HttpResponse] = {
    val url = location.url

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = url,
      entity = entity
    ).addHeader(Host(url.authority.host.address(), url.authority.port))
    val requestWithAuth = location.credentials.foldLeft(request)(
      (r, creds) => r.addHeader(Authorization(BasicHttpCredentials(creds.user, creds.password)))
    )

    logger.whenDebugEnabled(
      logger.debug(s"Post: $requestWithAuth")
    )
    sendReceive(requestWithAuth)
  }

  def unmarshalChronosResponse(
      entity: HttpEntity
  )(implicit actorSystem: ActorSystem): Future[List[ChronosJobLiveliness]] = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    import ChronosJobLiveliness._
    Unmarshal(entity).to[List[ChronosJobLiveliness]]
  }

  def unmarshalQueryResult(
      entity: HttpEntity
  )(implicit actorSystem: ActorSystem): Future[QueryResult] = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    Unmarshal(entity).to[QueryResult]
  }

  def checkHealth[F[_]: Effect](uri: String): HealthCheck[F, Id] = {
    implicit def backend: SttpBackend[F, Nothing] = AsyncHttpClientCatsBackend[F]()
    statusCodeHealthCheck[F, String](request.get(UriContext(StringContext(uri)).uri(uri)))
      .through(mods.recoverToSick)
  }

}
