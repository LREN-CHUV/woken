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
import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, Job, ValidationJob }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

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
                      "features_db",
                      "Sample",
                      "Sample",
                      "results_db",
                      "meta_db",
                      0.5,
                      512)

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(config)

  val variablesMetaService: VariablesMetaService[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]           = TestServices.featuresService

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
        targetTable = Some("unknown"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown"
    }

    "fail when the algorithm is unknown" in {
      val query = MiningQuery(
        user = user,
        variables = List(VariableId("score_test1")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some("Sample"),
        algorithm = AlgorithmSpec("unknown", Nil, None),
        datasets = Set(),
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
        targetTable = Some("Sample"),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = Set(),
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
        targetTable = Some("Sample"),
        algorithm = AlgorithmSpec("knn", Nil, None),
        datasets = Set(),
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
        targetTable = Some("Sample"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(DatasetId("Sample")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value._1 shouldBe a[DockerJob]

      val job: DockerJob = maybeJob.value._1.asInstanceOf[DockerJob]
      val feedback       = maybeJob.value._2
      val table          = TableId("features_db", None, "Sample")

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          // rm Dataset
          FeaturesQuery(
            List("score_test1"),
            List("stress_before_test1"),
            List(),
            table,
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

      job.query.sql shouldBe """SELECT "score_test1","stress_before_test1" FROM "Sample" WHERE "score_test1" IS NOT NULL AND "stress_before_test1" IS NOT NULL"""

      feedback shouldBe Nil
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
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = Set(DatasetId("desd-synthdata")),
        executionPlan = None
      )

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value._1 shouldBe a[DockerJob]

      val job: DockerJob = maybeJob.value._1.asInstanceOf[DockerJob]
      val feedback       = maybeJob.value._2
      val table          = TableId("features_db", None, "cde_features_a")

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            table,
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

      feedback shouldBe Nil
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

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value._1 shouldBe a[DockerJob]

      val job: DockerJob = maybeJob.value._1.asInstanceOf[DockerJob]
      val feedback       = maybeJob.value._2
      val table          = TableId("features_db", None, "cde_features_a")

      job.jobId should not be empty
      job.algorithmDefinition.dockerImage should startWith("hbpmip/python-knn")
      job should have(
        'query (
          FeaturesQuery(
            List("apoe4"),
            List("lefthippocampus"),
            List(),
            table,
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
        targetTable = Some("cde_features_a"),
        algorithm = AlgorithmSpec(ValidationJob.algorithmCode, Nil, None),
        datasets = Set(DatasetId("desd-synthdata")),
        executionPlan = None
      )
      val table = TableId("features_db", None, "cde_features_a")

      val maybeJob = queryToJobService.miningQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value._1 shouldBe a[ValidationJob[IO]]

      val job: ValidationJob[IO] = maybeJob.value._1.asInstanceOf[ValidationJob[IO]]
      val feedback               = maybeJob.value._2

      job.jobId should not be empty
      job should have(
        // TODO 'featuresTableService (table),
        'query (query),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )

      feedback shouldBe Nil
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
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

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
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
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
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", Nil, None)),
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
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
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec("knn", List(CodeValue("k", "5")), None)),
        trainingDatasets = Set(DatasetId("desd-synthdata")),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )
      val table = TableId("features_db", None, "cde_features_a")

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid

      val job: Job = maybeJob.value._1

      job.jobId should not be empty
      job should have(
        'inputTable (table),
        'query (query.copy(filters = apoe4LeftHippDesdFilter)),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
      // ExperimentQuery(UserId(test),List(VariableId(apoe4)),List(VariableId(lefthippocampus)),true,List(),None,Some(cde_features_a),Set(DatasetId(desd-synthdata)),Set(),List(AlgorithmSpec(knn,List(CodeValue(k,5)),None)),Set(),List(),None), instead of its expected value
      // ExperimentQuery(UserId(test),List(VariableId(apoe4)),List(VariableId(lefthippocampus)),true,List(),Some(CompoundFilterRule(AND,List(SingleFilterRule(apoe4,apoe4,string,text,is_not_null,List()), SingleFilterRule(lefthippocampus,lefthippocampus,string,text,is_not_null,List()), SingleFilterRule(dataset,dataset,string,text,in,List(desd-synthdata))))),Some(cde_features_a),Set(DatasetId(desd-synthdata)),Set(),List(AlgorithmSpec(knn,List(CodeValue(k,5)),None)),Set(),List(),None), on object ExperimentJob(06a5f472-f836-4fc9-b9d5-13a1ef49d100,TableId(features_db,None,cde_features_a),ExperimentQuery(UserId(test),List(VariableId(apoe4)),List(VariableId(lefthippocampus)),true,List(),None,Some(cde_features_a),Set(DatasetId(desd-synthdata)),Set(),List(AlgorithmSpec(knn,List(CodeValue(k,5)),None)),Set(),List(),None),Map(AlgorithmSpec(knn,List(CodeValue(k,5)),None) -> AlgorithmDefinition(knn,hbpmip/python-knn:0.4.0,true,false,false,Docker,ExecutionPlan(List(ExecutionStep(map,map,SelectDataset(training),Compute(compute-local)), ExecutionStep(reduce,reduce,PreviousResults(map),Compute(compute-global)))))),List(VariableMetaData(apoe4,ApoE4,polynominal,Some(int),Some(adni-merge),Some(Apolipoprotein E (APOE) e4 allele: is the strongest risk factor for Late Onset Alzheimer Disease (LOAD). At least one copy of APOE-e4 ),None,Some(List(EnumeratedValue(0,0), EnumeratedValue(1,1), EnumeratedValue(2,2))),None,None,None,None,Set()), VariableMetaData(lefthippocampus,Left Hippocampus,real,None,Some(lren-nmm-volumes),Some(),Some(cm3),None,None,None,None,None,Set())))
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
      val table = TableId("features_db", None, "cde_features_a")

      val maybeJob =
        queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe valid

      val job = maybeJob.value._1

      job.jobId should not be empty
      job should have(
        'inputTable (table),
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
        targetTable = Some("cde_features_a"),
        algorithms = List(AlgorithmSpec(ValidationJob.algorithmCode, Nil, None)),
        trainingDatasets = Set(DatasetId("desd-synthdata")),
        testingDatasets = Set(),
        validationDatasets = Set(),
        validations = Nil,
        executionPlan = None
      )

      val maybeJob = queryToJobService.experimentQuery2Job(query).unsafeRunSync()

      maybeJob shouldBe invalid
    }
  }

}
