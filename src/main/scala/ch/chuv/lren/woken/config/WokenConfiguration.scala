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

import cats.effect.{ IO, Sync }
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.utils.ConfigurationLoader
import com.typesafe.config.{ Config, ConfigFactory }

/** Global configuration of Woken
  *
  * @param config The user configuration at the source of this object
  */
case class WokenConfiguration(config: Config) {

  val app: AppConfiguration = AppConfiguration
    .read(config)
    .valueOr(configurationFailed)

  val databaseConfig: DatabaseId => Validation[DatabaseConfiguration] =
    DatabaseConfiguration.factory(config)

  val jobs: JobsConfiguration = JobsConfiguration
    .read(config)
    .valueOr(configurationFailed)

  val featuresDb: DatabaseConfiguration = databaseConfig(jobs.defaultFeaturesDatabase)
    .valueOr(configurationFailed)

  val wokenDb: DatabaseConfiguration = databaseConfig(DatabaseId("woken"))
    .valueOr(configurationFailed)

  val metaDb: DatabaseConfiguration = DatabaseConfiguration
    .factory(config)(jobs.metaDb)
    .valueOr(configurationFailed)

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(config)
}

object WokenConfiguration {

  /** Loads the standard configuration for Woken, using hard-coded defaults and configuration files.
    *
    * Order of loading:
    *
    * 1. Configuration of Akka pre-defined in woken.conf, configurable by environment variables
    * 2. Custom configuration defined in application.conf
    * 3. Configuration of Kamon pre-defined in kamon.conf, configurable by environment variables
    * 4. Default configuration for the algorithm library, it can be overriden in application.conf or individual versions
    *    for algorithms can be overriden by environment variables
    * 5. Common configuration for the Akka cluster, configuration by environment variables
    * 6. Reference configuration from the libraries
    *
    * @return an IO of WokenConfiguration as access to filesystem is required
    */
  def apply(): IO[WokenConfiguration] = Sync[IO].delay {

    val config: Config = {
      val appConfig = ConfigFactory
        .parseResourcesAnySyntax("woken.conf")
        .withFallback(ConfigFactory.defaultApplication())
        .withFallback(ConfigFactory.parseResourcesAnySyntax("kamon.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("algorithms.conf"))

      ConfigurationLoader.appendClusterConfiguration(appConfig).resolve()
    }

    WokenConfiguration(config)
  }

}
