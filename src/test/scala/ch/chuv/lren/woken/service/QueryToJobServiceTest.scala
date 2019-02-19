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
import ch.chuv.lren.woken.config.AlgorithmsConfiguration
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.VariablesMetaRepository
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables._
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.Predefined.Filters._

import org.scalatest.{ Matchers, WordSpec }

class QueryToJobServiceTest
    extends WordSpec
    with Matchers
    with ValidatedMatchers
    with ValidatedValues {

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(localNodeConfigSource)

  val variablesMetaService: VariablesMetaRepository[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]              = TestServices.featuresService

  val queryToJobService: QueryToJobService[IO] =
    QueryToJobService[IO](featuresService, variablesMetaService, jobsConf, algorithmLookup)

  "Transforming a mining query to a job" should {

    import ch.chuv.lren.woken.Predefined.MiningQueries._

    "fail when the target table is unknown" in {
      val maybeJob = queryToJobService.miningQuery2Job(unknownTableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown.public.unknown"
    }

    "fail when the algorithm is unknown" in {
      val maybeJob = queryToJobService.miningQuery2Job(unknownAlgorithmQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Could not find key: algorithms.unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the target variable is unknown" in {
      val maybeJob = queryToJobService.miningQuery2Job(unknownTargetVariableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Variable unknown do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when no covariables can be found" in {
      val maybeJob = queryToJobService.miningQuery2Job(allCovariablesUnknownQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Covariables unknown1,unknown2 do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when a covariable is unknown yet all must exist" in {
      val maybeJob =
        queryToJobService.miningQuery2Job(missingRequiredCovariableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Covariable unknown do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1
    }

    "create a DockerJob for a kNN algorithm on a table without dataset column" in {
      val maybeJob =
        queryToJobService.miningQuery2Job(queryTableWithoutDatasetColumn).unsafeRunSync()

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

      feedback shouldBe Nil
    }

    "create a DockerJob for a kNN algorithm on a table with several datasets" in {
      val maybeJob = queryToJobService.miningQuery2Job(knnOnTableWithDatasetQuery).unsafeRunSync()

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

      feedback shouldBe Nil
    }

    "drop the unknown covariables that do not need to exist" in {
      val maybeJob =
        queryToJobService.miningQuery2Job(queryWithSomeUnknownCovariables).unsafeRunSync()

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

      feedback shouldBe List(
        UserInfo("Covariable unknown do not exist in node testNode and table cde_features_a")
      )
    }

    "create a ValidationJob for a validation algorithm" in {
      val maybeJob = queryToJobService.miningQuery2Job(validationQuery).unsafeRunSync()

      maybeJob shouldBe valid
      maybeJob.value.job shouldBe a[ValidationJob[IO]]

      val job: ValidationJob[IO] = maybeJob.value.job.asInstanceOf[ValidationJob[IO]]
      val feedback               = maybeJob.value.feedback

      job.jobId should not be empty
      job should have(
        // TODO 'featuresTableService (table),
        'query (validationQuery),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )

      feedback shouldBe Nil
    }
  }

  "Transforming an experiment query to a job" should {

    import ch.chuv.lren.woken.Predefined.ExperimentQueries._

    "fail when the target table is unknown" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(unknownTableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Cannot find metadata for table unknown.public.unknown"
    }

    "fail when the algorithm is unknown" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(unknownAlgorithmQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Could not find key: algorithms.unknown"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when the target variable is unknown" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(unknownTargetVariableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Variable unknown do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when all covariables are unknown" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(allCovariablesUnknownQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Covariables unknown1,unknown2 do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1

    }

    "fail when the covariable is unknown yet must exist" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(missingRequiredCovariableQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "Covariable unknown do not exist in node testNode and table sample_data"
      maybeJob.invalidValue.size shouldBe 1
    }

    "fail when no algorithm has been selected" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(noAlgorithmQuery).unsafeRunSync()

      maybeJob shouldBe invalid
      maybeJob.invalidValue.head shouldBe "No algorithm defined"
      maybeJob.invalidValue.size shouldBe 1

    }

    "create a DockerJob for a kNN algorithm" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(knnAlgorithmCdeQuery).unsafeRunSync()

      maybeJob shouldBe valid

      val job: ExperimentJob = maybeJob.value.job

      job.jobId should not be empty
      job should have(
        'inputTable (cdeFeaturesATableId),
        'query (knnAlgorithmCdeQuery.copy(filters = apoe4LeftHippDesdFilter)),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "drop the unknown covariables that do not need to exist" in {
      val maybeJob =
        queryToJobService.experimentQuery2Job(withUnknownCovariablesQuery).unsafeRunSync()

      maybeJob shouldBe valid

      val job = maybeJob.value.job

      job.jobId should not be empty
      job should have(
        'inputTable (cdeFeaturesATableId),
        'query (
          withUnknownCovariablesQuery.copy(covariables = List(VariableId("lefthippocampus")),
                                           filters = apoe4LeftHippDesdFilter)
        ),
        'metadata (List(CdeVariables.apoe4, CdeVariables.leftHipocampus))
      )
    }

    "not accept a validation algorithm" in {
      val maybeJob = queryToJobService.experimentQuery2Job(validationQuery).unsafeRunSync()

      maybeJob shouldBe invalid
    }
  }

}
