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
      Container(`type` = "DOCKER", image = job.dockerImage)
    )(
      bridge =>
        Container(`type` = "DOCKER",
                  image = job.dockerImage,
                  network = Some("BRIDGE"),
                  parameters = List(DP("network", bridge)))
    )
    // On Federation, use the federationDb, otherwise look for the input db in the task or in the configuration of the node
    // TODO: something!
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
      name = job.jobNameResolved,
      command = "compute",
      shell = false,
      schedule = "R1//PT24H",
      epsilon = Some("PT5M"),
      runAsUser = Some("root"),
      container = Some(container),
      cpus = Some(0.5),
      mem = Some(512),
      owner = Some(jobs.owner),
      environmentVariables = environmentVariables
    )
  }
}
