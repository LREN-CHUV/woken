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

/**
  * Configuration for the jobs executing algorithms on this node
  *
  * @param node Name of the current node used for computations
  * @param owner Owner of the job, can be an email address
  * @param chronosServerUrl URL to Chronos server used to launch Docker containers in the Mesos cluster
  * @param featuresDb Configuration alias of the database containing features
  * @param resultDb Configuration alias of the database used to store results
  * @param defaultJobMemory Default memory in Mb allocated to a job
  * @param defaultJobCpus Default share of CPUs allocated to a job
  */
final case class JobsConfiguration(
    node: String,
    owner: String,
    chronosServerUrl: String,
    featuresDb: String,
    featuresTable: String,
    metadataKeyForFeaturesTable: String,
    resultDb: String,
    metaDb: String,
    defaultJobCpus: Double,
    defaultJobMemory: Int
)

object JobsConfiguration {

  def read(config: Config, path: List[String] = List("jobs")): Validation[JobsConfiguration] = {
    val jobsConfig = config.validateConfig(path.mkString("."))

    jobsConfig.andThen { jobs =>
      val node             = jobs.validateString("node")
      val owner            = jobs.validateString("owner")
      val chronosServerUrl = jobs.validateString("chronosServerUrl")
      val featuresDb       = jobs.validateString("featuresDb")
      val featuresTable    = jobs.validateString("featuresTable")
      val metadataKeyForFeaturesTable: Validation[String] =
        jobs.validateString("metadataKeyForFeaturesTable").orElse(featuresTable)
      val resultDb = jobs.validateString("resultDb")
      val metaDb   = jobs.validateString("metaDb")
      val cpus: Validation[Double] = jobs.validateDouble("default_job_cpus").orElse(lift(0.5))
      val mem: Validation[Int] = jobs.validateInt("default_job_memory").orElse(lift(512))

      (node,
       owner,
       chronosServerUrl,
       featuresDb,
       featuresTable,
       metadataKeyForFeaturesTable,
       resultDb,
       metaDb,
       cpus,
       mem) mapN JobsConfiguration.apply
    }
  }

}
