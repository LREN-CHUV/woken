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

package ch.chuv.lren.woken
import cats.Id
import cats.effect.Effect
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sup._
import sup.modules.sttp._
import com.softwaremill.sttp.{ sttp => request, _ }

/**
  * Remote APIs for Woken.
  *
  * This package contains the various APIs that can be used to drive Woken, including
  * Akka API, HTTP and Web sockets API.
  */
package object api {

  def healthCheck[F[_]: Effect](resource: String): HealthCheck[F, Id] = {
    implicit def backend: SttpBackend[F, Nothing] = AsyncHttpClientCatsBackend[F]()
    statusCodeHealthCheck[F, String](request.get(UriContext(StringContext(resource)).uri(resource)))
  }

}
