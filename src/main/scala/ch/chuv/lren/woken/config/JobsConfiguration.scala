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
import cats.data.Validated._
import cats.implicits._
import ch.chuv.lren.woken.messages.datasets.TableId

/**
  * Configuration for the jobs executing algorithms on this node
  *
  * @param node Name of the current node used for computations
  * @param owner Owner of the job, can be an email address
  * @param chronosServerUrl URL to Chronos server used to launch Docker containers in the Mesos cluster
  * @param defaultFeaturesDatabase Default database to use when looking for a table containing features
  * @param defaultFeaturesTable Pointer to the table containing features
  * @param resultDb Configuration alias of the database used to store results
  * @param defaultJobMemory Default memory in Mb allocated to a job
  * @param defaultJobCpus Default share of CPUs allocated to a job
  */
final case class JobsConfiguration(
    node: String,
    owner: String,
    chronosServerUrl: String,
    defaultFeaturesDatabase: DatabaseId,
    defaultFeaturesTable: TableId,
    resultDb: DatabaseId,
    metaDb: DatabaseId,
    defaultJobCpus: Double,
    defaultJobMemory: Int
)

object JobsConfiguration {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def read(config: Config, path: List[String] = List("jobs")): Validation[JobsConfiguration] = {
    val jobsConfig = config.validateConfig(path.mkString("."))

    jobsConfig.andThen { jobs =>
      val node                    = jobs.validateString("node")
      val owner                   = jobs.validateString("owner")
      val chronosServerUrl        = jobs.validateString("chronosServerUrl")
      val defaultFeaturesDatabase = jobs.validateString("defaultFeaturesDatabase").map(DatabaseId)
      val defaultFeaturesTable: Validation[TableId] = jobs
        .validateConfig("defaultFeaturesTable")
        .andThen(
          tableConfig => defaultFeaturesDatabase.andThen(db => tableIdConfig(db, tableConfig))
        )
      val resultDb = jobs.validateString("resultDb").map(DatabaseId)
      val metaDb   = jobs.validateString("metaDb").map(DatabaseId)
      val cpus: Validation[Double] =
        jobs.validateDouble("defaultJobCpus").orElse(0.5.validNel[String])
      val mem: Validation[Int] = jobs.validateInt("defaultJobMemory").orElse(512.validNel[String])

      (node,
       owner,
       chronosServerUrl,
       defaultFeaturesDatabase,
       defaultFeaturesTable,
       resultDb,
       metaDb,
       cpus,
       mem) mapN JobsConfiguration.apply
    }
  }

  private def tableIdConfig(defaultFeatureDatabase: DatabaseId,
                            tableConfig: Config): Validation[TableId] = {
    val name   = tableConfig.validateString("name")
    val schema = tableConfig.validateOptionalString("schema").map(_.getOrElse("public"))
    val database =
      tableConfig.validateOptionalString("database").map(_.getOrElse(defaultFeatureDatabase.code))

    (database, schema, name) mapN TableId.apply
  }
}
