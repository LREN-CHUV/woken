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

package ch.chuv.lren.woken.authentication

import akka.http.scaladsl.server.directives.Credentials
import ch.chuv.lren.woken.config.AppConfiguration

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Simple support for basic authentication.
  */
trait BasicAuthenticator {

  def appConfiguration: AppConfiguration

  def basicAuthenticator(
      credentials: Credentials
  )(implicit executionContext: ExecutionContext): Future[Option[String]] =
    credentials match {
      case cred @ Credentials.Provided(id) =>
        Future {
          if (cred.verify(appConfiguration.basicAuth.password)) Some(id) else None
        }
      case _ => Future.successful(None)
    }
}
