/*
 * Copyright 2017 LREN CHUV
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

package eu.hbp.mip.woken.core.model

import eu.hbp.mip.woken.api.JobDto
import eu.hbp.mip.woken.config.WokenConfig
import eu.hbp.mip.woken.core.model.{ DockerParameter => DP, EnvironmentVariable => EV }

object JobToChronos {

  import WokenConfig._

  def dbEnvironment(dbAlias: String, prefix: String = ""): List[EV] = {
    val conf = dbConfig(dbAlias)
    List(
      EV(prefix + "JDBC_DRIVER", conf.jdbcDriver),
      EV(prefix + "JDBC_JAR_PATH", conf.jdbcJarPath),
      EV(prefix + "JDBC_URL", conf.jdbcUrl),
      EV(prefix + "JDBC_USER", conf.jdbcUser),
      EV(prefix + "JDBC_PASSWORD", conf.jdbcPassword)
    )
  }

  def enrich(job: JobDto): ChronosJob = {

    val container = app.dockerBridgeNetwork.fold(
      Container("DOCKER", job.dockerImage, None, List())
    )(bridge => Container("DOCKER", job.dockerImage, Some("BRIDGE"), List(DP("network", bridge))))
    // On Federation, use the federationDb, otherwise look for the input db in the task or in the configuration of the node
    val inputDb = jobs.federationDb orElse job.inputDb orElse jobs.ldsmDb getOrElse (throw new IllegalArgumentException(
      "federationDb or ldsmDb should be defined in the configuration"
    ))
    val outputDb = jobs.resultDb

    val environmentVariables: List[EV] = List(EV("JOB_ID", job.jobId),
                                              EV("NODE", jobs.node),
                                              EV("DOCKER_IMAGE", job.dockerImage)) ++
      job.parameters.toList.map(kv => EV(kv._1, kv._2)) ++
      dbEnvironment(inputDb, "IN_") ++
      dbEnvironment(outputDb, "OUT_")

    ChronosJob(
      schedule = "R1//PT24H",
      epsilon = "PT5M",
      name = job.jobNameResolved,
      command = "compute",
      shell = false,
      runAsUser = "root",
      container = container,
      cpus = "0.5",
      mem = "512",
      uris = List(),
      async = false,
      owner = jobs.owner,
      environmentVariables = environmentVariables
    )
  }
}
