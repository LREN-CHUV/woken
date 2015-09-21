package core.model

import api.JobDto
import config.Config
import models.{ChronosJob, Container, EnvironmentVariable => EV}

object JobToChronos {

  import Config._

  def dbEnvironment(dbAlias: String, prefix: String = ""): List[EV] = {
    val dbConfig = getDbConfig(dbAlias)
    List(
      EV(prefix + "JDBC_DRIVER", dbConfig.jdbcDriver),
      EV(prefix + "JDBC_JAR_PATH", dbConfig.jdbcJarPath),
      EV(prefix + "JDBC_URL", dbConfig.jdbcUrl),
      EV(prefix + "JDBC_USER", dbConfig.jdbcUser),
      EV(prefix + "JDBC_PASSWORD", dbConfig.jdbcPassword)
    )
  }

  def enrich(job: JobDto): ChronosJob = {

    val container = Container("DOCKER", job.dockerImage)
    val environmentVariables: List[EV] = List(
      EV("REQUEST_ID", job.requestId),
      EV("NODE", jobs.node)) ++
         job.parameters.toList.map(kv => EV(kv._1, kv._2)) ++
         job.inputDb.fold(List[EV]())(dbEnvironment(_, "IN_")) ++
         job.outputDb.fold(List[EV]())(dbEnvironment(_, "OUT_"))

    ChronosJob(
      schedule = "R1//PT24H",
      epsilon = "PT5M",
      name = job.dockerImage.replace("registry.federation.mip.hbp/hbp_", "").takeWhile(_ != ':').replaceAll("/", "-"),
      command = "compute",
      shell = false,
      runAsUser = "root",
      container = container,
      cpus = "0.5",
      mem = "128",
      uris = List(),
      async = false,
      owner = jobs.owner,
      environmentVariables = environmentVariables
    )
  }
}
