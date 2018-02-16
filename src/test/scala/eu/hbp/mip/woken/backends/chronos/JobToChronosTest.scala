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
import eu.hbp.mip.woken.config.{ DatabaseConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.filters.{ InputType, Operator, SingleFilterRule }
import ch.chuv.lren.woken.messages.variables.VariableId
import eu.hbp.mip.woken.core.features.Queries._
import org.scalatest.{ FlatSpec, Matchers }
import spray.json._
import cats.data.ValidatedNel
import cats.syntax.validated._
import eu.hbp.mip.woken.core.features.FeaturesQuery

class JobToChronosTest extends FlatSpec with Matchers {

  val algorithm: AlgorithmSpec = AlgorithmSpec(
    code = "knn",
    parameters = List(CodeValue("k", "5"), CodeValue("n", "1"))
  )

  val user: UserId = UserId("test")

  // a < 10
  private val rule =
    SingleFilterRule("a", "a", "number", InputType.number, Operator.less, List("10"))

  val query: MiningQuery = MiningQuery(
    user = user,
    variables = List("target").map(VariableId),
    covariables = List("a", "b", "c").map(VariableId),
    grouping = List("grp1", "grp2").map(VariableId),
    filters = Some(rule),
    targetTable = None,
    datasets = Set(),
    algorithm = algorithm,
    executionPlan = None
  )

  val featuresQuery: FeaturesQuery =
    query.features("features_table", excludeNullValues = true, None)

  val jdbcConfs: Map[String, ValidatedNel[String, DatabaseConfiguration]] = Map(
    "features_db" -> DatabaseConfiguration(
      dbiDriver = "PostgreSQL",
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/features",
      host = "localhost",
      port = 5432,
      user = "user",
      password = "test"
    ).validNel,
    "woken_db" -> DatabaseConfiguration(
      dbiDriver = "PostgreSQL",
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/woken",
      host = "localhost",
      port = 5432,
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
    featuresTable = "features",
    metadataKeyForFeaturesTable = "features",
    resultDb = "woken_db",
    metaDb = "meta_db"
  )

  "A generic Docker job" should "be converted to a Chronos job definition" in {

    val dockerJob = DockerJob(
      jobId = "1234",
      dockerImage = "hbpmpi/test",
      inputDb = "features_db",
      query = featuresQuery,
      algorithmSpec = query.algorithm,
      metadata = metadata
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
        """SELECT "target","a","b","c","grp1","grp2" FROM features_table WHERE "target" IS NOT NULL AND "a" IS NOT NULL AND "b" IS NOT NULL AND "c" IS NOT NULL AND "grp1" IS NOT NULL AND "grp2" IS NOT NULL AND "a" < 10"""
      ),
      EnvironmentVariable("MODEL_PARAM_n", "1"),
      EnvironmentVariable("PARAM_MODEL_k", "5"),
      EnvironmentVariable("PARAM_grouping", "grp1,grp2"),
      EnvironmentVariable(
        "PARAM_meta",
        """{"grp2":{"type":"string"},"a":{"type":"string"},"grp1":{"type":"string"},"b":{"type":"string"},"target":{"type":"string"},"c":{"type":"string"}}"""
      ),
      EnvironmentVariable("PARAM_covariables", "a,b,c"),
      EnvironmentVariable("IN_DBI_DRIVER", "PostgreSQL"),
      EnvironmentVariable("IN_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("IN_JDBC_URL", "jdbc:postgres:localhost:5432/features"),
      EnvironmentVariable("IN_HOST", "localhost"),
      EnvironmentVariable("IN_PORT", "5432"),
      EnvironmentVariable("IN_USER", "user"),
      EnvironmentVariable("IN_PASSWORD", "test"),
      EnvironmentVariable("IN_JDBC_USER", "user"),
      EnvironmentVariable("IN_JDBC_PASSWORD", "test"),
      EnvironmentVariable("OUT_DBI_DRIVER", "PostgreSQL"),
      EnvironmentVariable("OUT_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("OUT_JDBC_URL", "jdbc:postgres:localhost:5432/woken"),
      EnvironmentVariable("OUT_HOST", "localhost"),
      EnvironmentVariable("OUT_PORT", "5432"),
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
      query = featuresQuery,
      algorithmSpec = query.algorithm,
      metadata = metadata
    )

    val chronosJob = JobToChronos(dockerJob, None, jobsConf, jdbcConfs.apply)

    assert(chronosJob.isInvalid)
  }
}
