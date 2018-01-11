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

package eu.hbp.mip.woken.config

import com.typesafe.config.Config
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import cats.data.Validated._
import cats.implicits._

case class BasicAuthentication(username: String, password: String)

case class MasterRouterConfig(miningActorsLimit: Int, experimentActorsLimit: Int)

case class AppConfiguration(
    clusterSystemName: String,
    dockerBridgeNetwork: Option[String],
    networkInterface: String,
    webServicesPort: Int,
    webServicesHttps: Boolean,
    disableWorkers: Boolean,
    jobServiceName: String,
    basicAuth: BasicAuthentication,
    masterRouterConfig: MasterRouterConfig
)

object AppConfiguration {

  def read(config: Config, path: List[String] = List("app")): Validation[AppConfiguration] = {
    val appConfig = config.validateConfig(path.mkString("."))

    appConfig.andThen { app =>
      val clusterSystemName   = app.validateString("clusterSystemName")
      val dockerBridgeNetwork = app.validateOptionalString("dockerBridgeNetwork")
      val networkInterface    = app.validateString("networkInterface")
      val port                = app.validateInt("webServicesPort")
      val jobServiceName      = app.validateString("jobServiceName")

      val https: Validation[Boolean] = app.validateBoolean("webServicesHttps").orElse(lift(true))
      val disableWorkers: Validation[Boolean] =
        app.validateBoolean("disableWorkers").orElse(lift(false))

      val basicAuth: Validation[BasicAuthentication] = app.validateConfig("basicAuth").andThen {
        c =>
          val username = c.validateString("username")
          val password = c.validateString("password")
          (username, password) mapN BasicAuthentication.apply
      }

      val masterRouterConfig: Validation[MasterRouterConfig] =
        app.validateConfig("master.router.actors").andThen { c =>
          val miningActorsLimit     = c.validateInt("mining.limit")
          val experimentActorsLimit = c.validateInt("experiment.limit")
          (miningActorsLimit, experimentActorsLimit) mapN MasterRouterConfig.apply
        }

      (clusterSystemName,
       dockerBridgeNetwork,
       networkInterface,
       port,
       https,
       disableWorkers,
       jobServiceName,
       basicAuth,
       masterRouterConfig) mapN AppConfiguration.apply
    }
  }

}
