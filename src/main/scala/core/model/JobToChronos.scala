package core.model

import api.JobDto
import config.Config
import config.MetaDatabaseConfig
import models.{ChronosJob, Container, EnvironmentVariable => EV}

object JobToChronos {

  import Config._

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

    val container = Container("DOCKER", job.dockerImage)
    // On Federation, use the federationDb, otherwise look for the input db in the task or in the configuration of the node
    val inputDb = jobs.federationDb orElse job.inputDb orElse jobs.ldsmDb getOrElse (throw new IllegalArgumentException("federationDb or ldsmDb should be defined in the configuration"))
    val outputDb = jobs.resultDb

    val environmentVariables: List[EV] = List(
      EV("JOB_ID", job.jobId),
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
