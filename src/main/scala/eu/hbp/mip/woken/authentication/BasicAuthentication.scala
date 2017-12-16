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

package eu.hbp.mip.woken.authentication

import akka.http.scaladsl.server.directives.Credentials
import eu.hbp.mip.woken.config.AppConfiguration

import scala.concurrent.{ExecutionContext, Future}

/**
  * Simple support for basic authentication.
  */
trait BasicAuthentication {

  def appConfiguration: AppConfiguration

  def basicAuthenticator(
                          credentials: Credentials
                        )(implicit executionContext: ExecutionContext): Future[Option[String]] =
    credentials match {
      case cred@Credentials.Provided(id) =>
        Future {
          if (cred.verify(appConfiguration.basicAuth.password)) Some(id) else None
        }
      case _ => Future.successful(None)
    }
}
