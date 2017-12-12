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

import com.typesafe.config.{ Config, ConfigFactory }
import eu.hbp.mip.woken.cromwell.util.ConfigUtil._

object WokenConfig {
  private val config = ConfigFactory.load()

  @deprecated
  object app {
    val appConf: Config = config.getConfig("app")

    val systemName: String                  = appConf.getString("systemName")
    val dockerBridgeNetwork: Option[String] = appConf.getStringOption("dockerBridgeNetwork")
    val interface: String                   = appConf.getString("interface")
    val port: Int                           = appConf.getInt("port")
    val jobServiceName: String              = appConf.getString("jobServiceName")
    val basicAuthUsername: String           = appConf.getString("basicAuth.username")
    val basicAuthPassword: String           = appConf.getString("basicAuth.password")

    case class MasterRouterConfig(miningActorsLimit: Int, experimentActorsLimit: Int)

    def masterRouterConfig: MasterRouterConfig = {
      val conf: Config = appConf.getConfig("master.router.actors")
      MasterRouterConfig(
        miningActorsLimit = conf.getInt("mining.limit"),
        experimentActorsLimit = conf.getInt("experiment.limit")
      )
    }

  }

  object defaultSettings {
    lazy val defaultSettingsConf: Config = config.getConfig("defaultSettings")
    lazy val requestConfig: Config       = defaultSettingsConf.getConfig("request")
    lazy val mainTable: String           = requestConfig.getString("mainTable")

    def dockerImage(algorithm: String): String =
      requestConfig.getConfig("functions").getConfig(algorithm).getString("image")

    def isPredictive(algorithm: String): Boolean =
      requestConfig.getConfig("functions").getConfig(algorithm).getBoolean("predictive")

    lazy val defaultDb: String     = requestConfig.getString("inDb")
    lazy val defaultMetaDb: String = requestConfig.getString("metaDb")
  }

}
