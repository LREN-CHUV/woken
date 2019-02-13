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

  val resultsDb: DatabaseConfiguration = databaseConfig(DatabaseId("woken"))
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
    * 1. Akka configuration hard-coded for clustering (present here to avoid clashes with tests no using a cluster)
    * 2. Configuration of Akka pre-defined in akka.conf, configurable by environment variables
    * 3. Configuration of Kamon pre-defined in kamon.conf, configurable by environment variables
    * 4. Custom configuration defined in application.conf and backed by reference.conf from the libraries
    * 5. Default configuration for the algorithm library, it can be overriden in application.conf or individual versions
    *    for algorithms can be overriden by environment variables
    *
    * @return an IO of WokenConfiguration as access to filesystem is required
    */
  def apply(): IO[WokenConfiguration] = Sync[IO].delay {

    val config: Config = {
      val remotingConfig = ConfigFactory.parseResourcesAnySyntax("akka-remoting.conf").resolve()
      val remotingImpl   = remotingConfig.getString("remoting.implementation")
      ConfigFactory
        .parseString("""
                     |akka {
                     |  actor.provider = cluster
                     |  extensions += "akka.cluster.pubsub.DistributedPubSub"
                     |  extensions += "akka.cluster.client.ClusterClientReceptionist"
                     |}
                   """.stripMargin)
        .withFallback(ConfigFactory.parseResourcesAnySyntax("akka.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax(s"akka-$remotingImpl-remoting.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("kamon.conf"))
        .withFallback(ConfigFactory.load())
        .withFallback(ConfigFactory.parseResourcesAnySyntax("algorithms.conf"))
        .resolve()
    }

    WokenConfiguration(config)
  }

}
