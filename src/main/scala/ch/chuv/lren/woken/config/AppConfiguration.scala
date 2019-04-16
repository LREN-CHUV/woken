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

package ch.chuv.lren.woken.config

import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.messages.remoting.BasicAuthentication

import cats.data.Validated._
import cats.implicits._

// TODO: review use of the following configuration elements: disableWorkers, jobServiceName

/** Configuration for the application
  *
  * @param clusterSystemName Name of the Akka cluster
  * @param dockerBridgeNetwork If Docker bridge networking is used, name of the bridge - to be used when spanning Docker containers
  * @param networkInterface Network interface to listen to for the web API.
  * @param webServicesPort Port used to expose services of the web API.
  * @param webServicesHttps If true, setup https for the web API.
  * @param disableWorkers If true, disable or ignore worker processes in the cluster (companion Woken validation processes)
  * @param basicAuth Authentication credentials for external access to Woken Web API
  */
case class AppConfiguration(
    clusterSystemName: String,
    dockerBridgeNetwork: Option[String],
    networkInterface: String,
    webServicesPort: Int,
    webServicesHttps: Boolean,
    disableWorkers: Boolean,
    basicAuth: BasicAuthentication
)

object AppConfiguration {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def read(config: Config, path: List[String] = List("app")): Validation[AppConfiguration] = {
    val appConfig = config.validateConfig(path.mkString("."))

    appConfig.andThen { app =>
      val clusterSystemName   = app.validateString("clusterSystemName")
      val dockerBridgeNetwork = app.validateOptionalString("dockerBridgeNetwork")
      val networkInterface    = app.validateString("networkInterface")
      val port                = app.validateInt("webServicesPort")

      val https: Validation[Boolean] =
        app.validateBoolean("webServicesHttps").orElse(true.validNel[String])
      val disableWorkers: Validation[Boolean] =
        app.validateBoolean("disableWorkers").orElse(false.validNel[String])

      val basicAuth: Validation[BasicAuthentication] = app.validateConfig("basicAuth").andThen {
        c =>
          val user     = c.validateString("user")
          val password = c.validateString("password")
          (user, password) mapN BasicAuthentication.apply
      }

      (
        clusterSystemName,
        dockerBridgeNetwork,
        networkInterface,
        port,
        https,
        disableWorkers,
        basicAuth
      ) mapN AppConfiguration.apply
    }
  }

}
