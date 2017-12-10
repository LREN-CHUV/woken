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

import cats.data.ValidatedNel
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.{ DatabaseConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.messages.external.{ Algorithm, MiningQuery, VariableId }
import org.scalatest.{ FlatSpec, Matchers }
import spray.json._
import cats.syntax.validated._

class JobToChronosTest extends FlatSpec with Matchers {

  val algorithm: Algorithm = Algorithm(
    code = "knn",
    name = "KNN",
    parameters = Map("k" -> "5", "n" -> "1")
  )

  val query: MiningQuery = MiningQuery(
    variables = List("target").map(VariableId),
    covariables = List("a", "b", "c").map(VariableId),
    grouping = List("grp1", "grp2").map(VariableId),
    filters = "a > 10",
    algorithm = algorithm
  )

  val jdbcConfs: Map[String, ValidatedNel[String, DatabaseConfiguration]] = Map(
    "features_db" -> DatabaseConfiguration(
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/features",
      user = "user",
      password = "test"
    ).validNel,
    "woken_db" -> DatabaseConfiguration(
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/woken",
      user = "woken",
      password = "wpwd"
    ).validNel
  ).withDefaultValue("".invalidNel)

  val metadata: JsObject = """
               |{
               |  "target": {"type": "string"},
               |  "a": {"type": "string"},
               |  "b": {"type": "string"},
               |  "c": {"type": "string"},
               |  "grp1": {"type": "string"},
               |  "grp2": {"type": "string"}
               |}
             """.stripMargin.parseJson.asJsObject

  val jobsConf: JobsConfiguration = JobsConfiguration(
    node = "test",
    owner = "mip@chuv.ch",
    chronosServerUrl = "http://localhost:4400",
    featuresDb = "features_db",
    resultDb = "woken_db"
  )

  "A generic Docker job" should "be converted to a Chronos job definition" in {

    val dockerJob = DockerJob(
      jobId = "1234",
      dockerImage = "hbpmpi/test",
      inputDb = "features_db",
      inputTable = "features_table",
      query = query,
      metadata = metadata,
      shadowOffset = None
    )

    val chronosJob = JobToChronos(dockerJob, None, jobsConf, jdbcConfs.apply)

    val environmentVariables = List(
      EnvironmentVariable("JOB_ID", "1234"),
      EnvironmentVariable("NODE", "test"),
      EnvironmentVariable("DOCKER_IMAGE", "hbpmpi/test"),
      EnvironmentVariable("PARAM_variables", "target"),
      EnvironmentVariable("MODEL_PARAM_k", "5"),
      EnvironmentVariable("PARAM_MODEL_n", "1"),
      EnvironmentVariable(
        "PARAM_query",
        "select target,a,b,c,grp1,grp2 from features_table where target is not null and a is not null and b is not null and c is not null and grp1 is not null and grp2 is not null and a > 10"
      ),
      EnvironmentVariable("MODEL_PARAM_n", "1"),
      EnvironmentVariable("PARAM_MODEL_k", "5"),
      EnvironmentVariable("PARAM_grouping", "grp1,grp2"),
      EnvironmentVariable(
        "PARAM_meta",
        """{"grp2":{"type":"string"},"a":{"type":"string"},"grp1":{"type":"string"},"b":{"type":"string"},"target":{"type":"string"},"c":{"type":"string"}}"""
      ),
      EnvironmentVariable("PARAM_covariables", "a,b,c"),
      EnvironmentVariable("IN_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("IN_JDBC_URL", "jdbc:postgres:localhost:5432/features"),
      EnvironmentVariable("IN_USER", "user"),
      EnvironmentVariable("IN_PASSWORD", "test"),
      EnvironmentVariable("IN_JDBC_USER", "user"),
      EnvironmentVariable("IN_JDBC_PASSWORD", "test"),
      EnvironmentVariable("OUT_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("OUT_JDBC_URL", "jdbc:postgres:localhost:5432/woken"),
      EnvironmentVariable("OUT_USER", "woken"),
      EnvironmentVariable("OUT_PASSWORD", "wpwd"),
      EnvironmentVariable("OUT_JDBC_USER", "woken"),
      EnvironmentVariable("OUT_JDBC_PASSWORD", "wpwd")
    )

    val expected = ChronosJob(
      name = "test_1234",
      description = None,
      command = "compute",
      arguments = Nil,
      shell = false,
      schedule = "R1//PT1M",
      epsilon = Some("PT5M"),
      executor = None,
      executorFlags = None,
      container = Some(
        Container(`type` = ContainerType.DOCKER, image = "hbpmpi/test", network = NetworkMode.HOST)
      ),
      cpus = Some(0.5),
      mem = Some(512.0),
      disk = None,
      owner = Some("mip@chuv.ch"),
      environmentVariables = environmentVariables,
      retries = 0
    )

    chronosJob.getOrElse(None) shouldBe expected
  }

  "An invalid Docker job using some unknown database" should "not be converted but marked as invalid" in {

    val dockerJob = DockerJob(
      jobId = "1234",
      dockerImage = "hbpmpi/test",
      inputDb = "unknown_db",
      inputTable = "features",
      query = query,
      metadata = metadata,
      shadowOffset = None
    )

    val chronosJob = JobToChronos(dockerJob, None, jobsConf, jdbcConfs.apply)

    assert(chronosJob.isInvalid)
  }
}
