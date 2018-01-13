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

/**
  * Configuration for the jobs executing algorithms on this node
  *
  * @param node Name of the current node used for computations
  * @param owner Owner of the job, can be an email address
  * @param chronosServerUrl URL to Chronos server used to launch Docker containers in the Mesos cluster
  * @param featuresDb Configuration alias of the database containing features
  * @param resultDb Configuration alias of the database used to store results
  */
final case class JobsConfiguration(
    node: String,
    owner: String,
    chronosServerUrl: String,
    featuresDb: String,
    featuresTable: String,
    metadataKeyForFeaturesTable: String,
    resultDb: String,
    metaDb: String
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

      (node,
       owner,
       chronosServerUrl,
       featuresDb,
       featuresTable,
       metadataKeyForFeaturesTable,
       resultDb,
       metaDb) mapN JobsConfiguration.apply
    }
  }

}
