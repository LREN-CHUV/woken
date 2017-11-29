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

package eu.hbp.mip.woken.backends.chronos

import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.{ JdbcConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.backends.chronos.{ EnvironmentVariable => EV }
import eu.hbp.mip.woken.config.WokenConfig.DbConfig
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import cats.implicits._

object JobToChronos {

  type DbBConfigF = String => DbConfig

  private[this] def dbEnvironment(conf: JdbcConfiguration,
                                  prefix: String = ""): List[EnvironmentVariable] =
    List(
      EV(prefix + "JDBC_DRIVER", conf.jdbcDriver),
      EV(prefix + "JDBC_URL", conf.jdbcUrl),
      EV(prefix + "JDBC_USER", conf.jdbcUser),
      EV(prefix + "JDBC_PASSWORD", conf.jdbcPassword)
    )

  def apply(job: DockerJob,
            dockerBridgeNetwork: Option[String],
            jobsConf: JobsConfiguration,
            jdbcConfF: String => Validation[JdbcConfiguration]): Validation[ChronosJob] = {

    val container = dockerBridgeNetwork.fold(
      Container(`type` = ContainerType.DOCKER, image = job.dockerImage)
    )(
      bridge =>
        // LATER: adding --network=<bridge> is still required, despite having the information in networkInfos
        // networkInfos = List(Network(name = bridge)),
        Container(`type` = ContainerType.DOCKER,
                  image = job.dockerImage,
                  network = NetworkMode.BRIDGE,
                  parameters = List(Parameter("network", bridge)))
    )

    def buildChronosJob(inputDb: JdbcConfiguration, outputDb: JdbcConfiguration): ChronosJob = {
      val environmentVariables: List[EV] = List(
        EV("JOB_ID", job.jobId),
        EV("NODE", jobsConf.node),
        EV("DOCKER_IMAGE", job.dockerImage)
      ) ++
        job.dockerParameters.map(kv => EV(kv._1, kv._2)) ++
        dbEnvironment(inputDb, "IN_") ++
        dbEnvironment(outputDb, "OUT_")

      // TODO: add config parameter for CPU and mem, mem should come from Docker image metadata or json descriptor
      ChronosJob(
        name = job.jobName,
        command = "compute",
        shell = false,
        schedule = "R1//PT1M",
        epsilon = Some("PT5M"),
        container = Some(container),
        cpus = Some(0.5),
        mem = Some(512),
        owner = Some(jobsConf.owner),
        environmentVariables = environmentVariables
      )
    }

    val inputDb  = jdbcConfF(job.inputDb)
    val outputDb = jdbcConfF(jobsConf.resultDb)

    (inputDb, outputDb) mapN buildChronosJob

  }
}
