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

package ch.chuv.lren.woken.api

import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, AlgorithmsConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.core.ExperimentActor
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.{ CdeVariables, DockerJob, ValidationJob }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables._
import ch.chuv.lren.woken.service.TestServices
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

class MiningQueriesTest extends WordSpec with Matchers with ValidatedMatchers with ValidatedValues {

  val config: Config = ConfigFactory
    .parseResourcesAnySyntax("localDatasets.conf")
    .withFallback(ConfigFactory.load("algorithms.conf"))
    .withFallback(ConfigFactory.load("test.conf"))
    .resolve()

  val user: UserId = UserId("test")

  val jobsConf =
    JobsConfiguration("testNode",
                      "admin",
                      "http://chronos",
                      "featuresDb",
                      "features",
                      "features",
                      "resultsDb",
                      "metaDb",
                      0.5,
                      512)

  private val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(config)

  "Transforming a mining query to a job" should {

    "fail when the target table is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("unknown"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown"
    }

    "fail when the algorithm is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("unknown", Nil, None),
        datasets = Set(),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Could not find key: algorithms.unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the target variable is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("unknown")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = Set(),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the covariable is unknown yet must exist" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("unknown")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = Set(),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "create a DockerJob for a kNN algorithm" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe valid
      maybeJob.value shouldBe a[DockerJob]

      val job: DockerJob = maybeJob.value.asInstanceOf[DockerJob]

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'inputDb ("featuresDb"),
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            "cde_features_a",
            """SELECT "apoe4","lefthippocampus" FROM cde_features_a WHERE "apoe4" IS NOT NULL AND "lefthippocampus" IS NOT NULL AND "dataset" IN ('desd-synthdata')"""
          )
        ),
        'algorithmSpec (
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        ),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "drop the unknown covariables that do not need to exist" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus"), VariableId("unknown")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe valid
      maybeJob.value shouldBe a[DockerJob]

      val job: DockerJob = maybeJob.value.asInstanceOf[DockerJob]

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'inputDb ("featuresDb"),
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            "cde_features_a",
            """SELECT "apoe4","lefthippocampus" FROM cde_features_a WHERE "apoe4" IS NOT NULL AND "lefthippocampus" IS NOT NULL AND "dataset" IN ('desd-synthdata')"""
          )
        ),
        'algorithmSpec (
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        ),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "create a ValidationJob for a validation algorithm" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec(ValidationJob.algorithmCode, Nil, None),
        datasets = Set(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = MiningQueries.miningQuery2Job(TestServices.localVariablesMetaService,
                                                   jobsConf,
                                                   algorithmLookup)(query)

      maybeJob shouldBe valid
      maybeJob.value shouldBe a[ValidationJob]

      val job: ValidationJob = maybeJob.value.asInstanceOf[ValidationJob]

      job.jobId should not be empty
      job should have(
        'inputDb ("featuresDb"),
        'inputTable ("cde_features_a"),
        'query (query),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

  }

  "Transforming an experiment query to a job" should {

    "fail when the target table is unknown" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("unknown"),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown"
    }

    "fail when the algorithm is unknown" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("unknown", Nil, None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Could not find key: algorithms.unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the target variable is unknown" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("unknown")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the covariable is unknown yet must exist" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("unknown")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "create a DockerJob for a kNN algorithm" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = Set(DatasetId("desd-synthdata")),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe valid

      val job = maybeJob.value

      job.jobId should not be empty
      job should have(
        'inputDb ("featuresDb"),
        'inputTable ("cde_features_a"),
        'query (query),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "drop the unknown covariables that do not need to exist" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus"), VariableId("unknown")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = Set(DatasetId("desd-synthdata")),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe valid

      val job = maybeJob.value

      job.jobId should not be empty
      job should have(
        'inputDb ("featuresDb"),
        'inputTable ("cde_features_a"),
        'query (query.copy(covariables = List(VariableId("lefthippocampus")))),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "not accept a validation algorithm" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec(ValidationJob.algorithmCode, Nil, None)),
        trainingDatasets = Set(DatasetId("desd-synthdata")),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        MiningQueries.experimentQuery2Job(TestServices.localVariablesMetaService,
                                          jobsConf,
                                          algorithmLookup)(query)

      maybeJob shouldBe invalid
    }
  }

}
