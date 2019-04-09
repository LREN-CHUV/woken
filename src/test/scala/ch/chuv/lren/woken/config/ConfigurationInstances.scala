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

import ch.chuv.lren.woken.messages.datasets.TableId
import com.typesafe.config.{ Config, ConfigFactory }

object ConfigurationInstances {

  val noDbConfig =
    DatabaseConfiguration(
      id = DatabaseId("no_db"),
      dbiDriver = "DBI",
      dbApiDriver = "DBAPI",
      jdbcDriver = "java.lang.String",
      jdbcUrl = "",
      host = "",
      port = 0,
      database = "db",
      schema = "public",
      user = "",
      password = "",
      poolSize = 5,
      tables = Map()
    )

  val featuresDb = DatabaseId("features_db")
  val wokenDb    = DatabaseId("woken")
  val metaDb     = DatabaseId("meta")

  val unknownTableId                   = TableId("unknown", "unknown")
  val featuresTableId: TableId         = tableId("features")
  val churnDataTableId: TableId        = tableId("churn")
  val sampleDataTableId: TableId       = tableId("sample_data")
  val cdeFeaturesATableId: TableId     = tableId("cde_features_a")
  val cdeFeaturesBTableId: TableId     = tableId("cde_features_b")
  val cdeFeaturesCTableId: TableId     = tableId("cde_features_c")
  val cdeFeaturesMixedTableId: TableId = tableId("mip_cde_features")

  val noJobsConf =
    JobsConfiguration("testNode",
                      "noone",
                      "http://nowhere",
                      featuresDb,
                      featuresTableId,
                      wokenDb,
                      metaDb,
                      0.5,
                      512)
  val jobsConf =
    JobsConfiguration("testNode",
                      "admin",
                      "http://chronos",
                      featuresDb,
                      sampleDataTableId,
                      wokenDb,
                      metaDb,
                      0.5,
                      512)

  lazy val localNodeConfigSource: Config = ConfigFactory
    .parseResourcesAnySyntax("localDatasets.conf")
    .withFallback(ConfigFactory.load("algorithms.conf"))
    .withFallback(ConfigFactory.load("test.conf"))
    .resolve()

  lazy val localNodeConfig: WokenConfiguration = WokenConfiguration(localNodeConfigSource)

  lazy val centralNodeConfigSource: Config = ConfigFactory
    .parseResourcesAnySyntax("remoteDatasets.conf")
    .withFallback(ConfigFactory.load("algorithms.conf"))
    .withFallback(ConfigFactory.load("test.conf"))
    .resolve()

  lazy val centralNodeConfig: WokenConfiguration = WokenConfiguration(centralNodeConfigSource)

  lazy val featuresDbConfiguration: DatabaseConfiguration = localNodeConfig.featuresDb

  private def tableId(name: String) = TableId(featuresDb.code, name)
}
