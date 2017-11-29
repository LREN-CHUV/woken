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
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import cats.data.Validated._
import cats.implicits._

final case class JdbcConfiguration(jdbcDriver: String,
                                   jdbcUrl: String,
                                   jdbcUser: String,
                                   jdbcPassword: String)

object JdbcConfiguration {

  def read(config: Config, path: Seq[String]): Validation[JdbcConfiguration] = {
    val jdbcConfig = path.foldLeft(config) { (c, s) =>
      c.getConfig(s)
    }

    val jdbcDriver   = jdbcConfig.validateString("jdbc_driver")
    val jdbcUrl      = jdbcConfig.validateString("jdbc_url")
    val jdbcUser     = jdbcConfig.validateString("jdbc_user")
    val jdbcPassword = jdbcConfig.validateString("jdbc_password")

    (jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword) mapN JdbcConfiguration.apply
  }

  def factory(config: Config): String => Validation[JdbcConfiguration] =
    dbAlias => read(config, List("db", dbAlias))

  def main(args: Array[String]): Unit =
    print(factory(ConfigFactory.load())("ldsm"))

}
