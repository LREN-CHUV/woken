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

package ch.chuv.lren.woken.backends.chronos

import ch.chuv.lren.woken.config.{ DatabaseConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.filters.{ InputType, Operator, SingleFilterRule }
import ch.chuv.lren.woken.messages.variables.{ VariableId, VariableMetaData, VariableType }
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.DockerJob
import org.scalatest.{ FlatSpec, Matchers }
import cats.data.ValidatedNel
import cats.syntax.validated._

class JobToChronosTest extends FlatSpec with Matchers {

  val algorithm: AlgorithmSpec = AlgorithmSpec(
    code = "knn",
    parameters = List(CodeValue("k", "5"), CodeValue("n", "1")),
    step = None
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
    query
      .filterNulls(variablesCanBeNull = false, covariablesCanBeNull = false)
      .features("features_table", None)

  val jdbcConfs: Map[String, ValidatedNel[String, DatabaseConfiguration]] = Map(
    "features_db" -> DatabaseConfiguration(
      dbiDriver = "PostgreSQL",
      dbApiDriver = "postgresql",
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/features",
      host = "localhost",
      port = 5432,
      database = "features",
      user = "user",
      password = "test",
      poolSize = 5
    ).validNel,
    "woken_db" -> DatabaseConfiguration(
      dbiDriver = "PostgreSQL",
      dbApiDriver = "postgresql",
      jdbcDriver = "org.postgresql.Driver",
      jdbcUrl = "jdbc:postgres:localhost:5432/woken",
      host = "localhost",
      port = 5432,
      database = "woken",
      user = "woken",
      password = "wpwd",
      poolSize = 5
    ).validNel
  ).withDefaultValue("".invalidNel)

  val metadata: List[VariableMetaData] = List(
    VariableMetaData("target",
                     "target",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set()),
    VariableMetaData("a",
                     "a",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set()),
    VariableMetaData("b",
                     "b",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set()),
    VariableMetaData("c",
                     "c",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set()),
    VariableMetaData("grp1",
                     "grp1",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set()),
    VariableMetaData("grp2",
                     "grp2",
                     VariableType.text,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     None,
                     Set())
  )

  val jobsConf: JobsConfiguration = JobsConfiguration(
    node = "test",
    owner = "mip@chuv.ch",
    chronosServerUrl = "http://localhost:4400",
    featuresDb = "features_db",
    featuresTable = "features",
    metadataKeyForFeaturesTable = "features",
    resultDb = "woken_db",
    metaDb = "meta_db",
    1.0,
    256
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
      EnvironmentVariable("MODEL_PARAM_n", "1"),
      EnvironmentVariable(
        "PARAM_query",
        """SELECT "target","a","b","c","grp1","grp2" FROM features_table WHERE "target" IS NOT NULL AND "a" IS NOT NULL AND "b" IS NOT NULL AND "c" IS NOT NULL AND "grp1" IS NOT NULL AND "grp2" IS NOT NULL AND "a" < 10 ORDER BY abs(('x'||substr(md5(subjectcode),1,16))::bit(64)::BIGINT)"""
      ),
      EnvironmentVariable("PARAM_grouping", "grp1,grp2"),
      EnvironmentVariable(
        "PARAM_meta",
        """{"grp2":{"code":"grp2","label":"grp2","type":"text"},"a":{"code":"a","label":"a","type":"text"},"grp1":{"code":"grp1","label":"grp1","type":"text"},"b":{"code":"b","label":"b","type":"text"},"target":{"code":"target","label":"target","type":"text"},"c":{"code":"c","label":"c","type":"text"}}"""
      ),
      EnvironmentVariable("PARAM_covariables", "a,b,c"),
      EnvironmentVariable("IN_DBI_DRIVER", "PostgreSQL"),
      EnvironmentVariable("IN_DBAPI_DRIVER", "postgresql"),
      EnvironmentVariable("IN_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("IN_JDBC_URL", "jdbc:postgres:localhost:5432/features"),
      EnvironmentVariable("IN_HOST", "localhost"),
      EnvironmentVariable("IN_PORT", "5432"),
      EnvironmentVariable("IN_DATABASE", "features"),
      EnvironmentVariable("IN_USER", "user"),
      EnvironmentVariable("IN_PASSWORD", "test"),
      EnvironmentVariable("OUT_DBI_DRIVER", "PostgreSQL"),
      EnvironmentVariable("OUT_DBAPI_DRIVER", "postgresql"),
      EnvironmentVariable("OUT_JDBC_DRIVER", "org.postgresql.Driver"),
      EnvironmentVariable("OUT_JDBC_URL", "jdbc:postgres:localhost:5432/woken"),
      EnvironmentVariable("OUT_HOST", "localhost"),
      EnvironmentVariable("OUT_PORT", "5432"),
      EnvironmentVariable("OUT_DATABASE", "woken"),
      EnvironmentVariable("OUT_USER", "woken"),
      EnvironmentVariable("OUT_PASSWORD", "wpwd")
    ).sortBy(_.name)

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
      cpus = Some(1.0),
      mem = Some(256.0),
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
