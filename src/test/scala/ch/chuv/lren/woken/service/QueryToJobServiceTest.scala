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

package ch.chuv.lren.woken.service

import cats.effect.IO
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.config.{ AlgorithmsConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.VariablesMetaRepository
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables._
import ch.chuv.lren.woken.config.ConfigurationInstances._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.immutable.TreeSet

class QueryToJobServiceTest
    extends WordSpec
    with Matchers
    with ValidatedMatchers
    with ValidatedValues {

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
                      featuresDb,
                      sampleDataTableId,
                      wokenDb,
                      metaDb,
                      0.5,
                      512)

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(config)

  val variablesMetaService: VariablesMetaRepository[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]              = TestServices.featuresService

  val queryToJobService: QueryToJobService[IO] =
    QueryToJobService[IO](featuresService, variablesMetaService, jobsConf, algorithmLookup)

  val apoe4LeftHippDesdFilter = Some(
    CompoundFilterRule(
      Condition.and,
      List(
        SingleFilterRule("apoe4", "apoe4", "string", InputType.text, Operator.isNotNull, List()),
        SingleFilterRule("lefthippocampus",
                         "lefthippocampus",
                         "string",
                         InputType.text,
                         Operator.isNotNull,
                         List()),
        SingleFilterRule("dataset",
                         "dataset",
                         "string",
                         InputType.text,
                         Operator.in,
                         List("desd-synthdata"))
      )
    )
  )

  val scoreStressTest1Filter = Some(
    CompoundFilterRule(
      Condition.and,
      List(
        SingleFilterRule("score_test1",
                         "score_test1",
                         "string",
                         InputType.text,
                         Operator.isNotNull,
                         List()),
        SingleFilterRule("stress_before_test1",
                         "stress_before_test1",
                         "string",
                         InputType.text,
                         Operator.isNotNull,
                         List())
      )
    )
  )

  "Transforming a mining query to a job" should {

    "fail when the target table is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(unknownTableId),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = TreeSet(),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown.public.unknown"
    }

    "fail when the algorithm is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("score_test1")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithm = AlgorithmSpec("unknown", Nil, None),
        datasets = TreeSet(),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Could not find key: algorithms.unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the target variable is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("unknown")),
        covariables = List(VariableId("score_test1")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = TreeSet(),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the covariable is unknown yet must exist" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("score_test1")),
        covariables = List(VariableId("unknown")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = TreeSet(),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Found 1 out of 2 variables. Missing unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "create a DockerJob for a kNN algorithm on a table without dataset column" in {
      // variablesCanBeNull = false
      // covariablesCanBeNull = false

      val query = MiningQuery(
        user = user,
        variables = List(VariableId("score_test1")),
        covariables = List(VariableId("stress_before_test1")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = TreeSet(DatasetId("Sample")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value.job shouldBe a[MiningJob]

      val job: DockerJob = maybeJob.value.job.asInstanceOf[MiningJob].dockerJob
      val feedback       = maybeJob.value.feedback

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          // rm Dataset
          FeaturesQuery(
            List("score_test1"),
            List("stress_before_test1"),
            List(),
            sampleDataTableId,
            scoreStressTest1Filter,
            None,
            None
          )
        ),
        'algorithmSpec (
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        ),
        'metadata (List(SampleVariables.score_test1, SampleVariables.stress_before_test1))
      )

      job.query.sql shouldBe """SELECT "score_test1","stress_before_test1" FROM "sample_data" WHERE "score_test1" IS NOT NULL AND "stress_before_test1" IS NOT NULL"""

      feedback shouldBe List(UserInfo("Missing variables stress_before_test1"))
    }

    "create a DockerJob for a kNN algorithm on a table with several datasets" in {
      // variablesCanBeNull = false
      // covariablesCanBeNull = false

      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(cdeFeaturesATableId),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = TreeSet(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value.job shouldBe a[MiningJob]

      val job: DockerJob = maybeJob.value.job.asInstanceOf[MiningJob].dockerJob
      val feedback       = maybeJob.value.feedback

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            cdeFeaturesATableId,
            apoe4LeftHippDesdFilter,
            None,
            None
          )
        ),
        'algorithmSpec (
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        ),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )

      job.query.sql shouldBe """SELECT "apoe4","lefthippocampus" FROM "cde_features_a" WHERE "apoe4" IS NOT NULL AND "lefthippocampus" IS NOT NULL AND "dataset" IN ('desd-synthdata')"""

      feedback shouldBe List(UserInfo("Missing variables lefthippocampus"))
    }

    "drop the unknown covariables that do not need to exist" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus"), VariableId("unknown")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(cdeFeaturesATableId),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = TreeSet(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value.job shouldBe a[MiningJob]

      val job: DockerJob = maybeJob.value.job.asInstanceOf[MiningJob].dockerJob
      val feedback       = maybeJob.value.feedback

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            cdeFeaturesATableId,
            apoe4LeftHippDesdFilter,
            None,
            None
          )
        ),
        'algorithmSpec (
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        ),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )

      job.query.sql shouldBe """SELECT "apoe4","lefthippocampus" FROM "cde_features_a" WHERE "apoe4" IS NOT NULL AND "lefthippocampus" IS NOT NULL AND "dataset" IN ('desd-synthdata')"""

      feedback shouldBe List(UserInfo("Missing variables lefthippocampus"))
    }

    "create a ValidationJob for a validation algorithm" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(cdeFeaturesATableId),
        algorithm = AlgorithmSpec(ValidationJob.algorithmCode, Nil, None),
        datasets = TreeSet(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value.job shouldBe a[ValidationJob[IO]]

      val job: ValidationJob[IO] = maybeJob.value.job.asInstanceOf[ValidationJob[IO]]
      val feedback               = maybeJob.value.feedback

      job.jobId should not be empty
      job should have(
        // TODO 'featuresTableService (table),
        'query (query),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )

      feedback shouldBe List(UserInfo("Missing variables lefthippocampus"))
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
        targetTable = Some(unknownTableId),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown.public.unknown"
    }

    "fail when the algorithm is unknown" in {
      val query = ExperimentQuery(
        user = user,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec("unknown", Nil, None)),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

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
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

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
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

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
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = TreeSet(DatasetId("desd-synthdata")),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid

      val job: ExperimentJob = maybeJob.value.job

      job.jobId should not be empty
      job should have(
        'inputTable (cdeFeaturesATableId),
        'query (query.copy(filters = apoe4LeftHippDesdFilter)),
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
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = TreeSet(DatasetId("desd-synthdata")),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid

      val job = maybeJob.value.job

      job.jobId should not be empty
      job should have(
        'inputTable (cdeFeaturesATableId),
        'query (
          query.copy(covariables = List(VariableId("lefthippocampus")),
                     filters = apoe4LeftHippDesdFilter)
        ),
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
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec(ValidationJob.algorithmCode, Nil, None)),
        trainingDatasets = TreeSet(DatasetId("desd-synthdata")),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob = queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
    }
  }

}
