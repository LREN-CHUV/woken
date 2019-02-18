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

package ch.chuv.lren.woken.mining

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import cats.effect.IO
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.Predefined.Algorithms.{ anovaFactorial, knnWithK5 }
import ch.chuv.lren.woken.core.model.jobs.{ ExperimentJobInProgress, _ }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{ FeaturesService, QueryToJobService, TestServices }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.backends.faas.{ AlgorithmExecutor, AlgorithmExecutorInstances }

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import ch.chuv.lren.woken.config.{ AlgorithmsConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.dao.VariablesMetaRepository
import ExperimentQuerySupport._

import scala.collection.immutable.TreeSet
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.messages.variables.VariableId
import org.scalamock.scalatest.MockFactory

/**
  * Experiment flow should always complete with success, but the error is reported inside the response.
  */
class LocalExperimentServiceTest
    extends TestKit(ActorSystem("ExperimentFlowTest"))
    with WordSpecLike
    with Matchers
    with ValidatedMatchers
    with ValidatedValues
    with BeforeAndAfterAll
    with MockFactory
    with JsonUtils
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = ExecutionContext.global

  val user: UserId = UserId("test")

  val jobsConf =
    JobsConfiguration("testNode",
                      "admin",
                      "http://chronos",
                      featuresDb,
                      cdeFeaturesATableId,
                      wokenDb,
                      metaDb,
                      0.5,
                      512)

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(localNodeConfigSource)

  val variablesMetaService: VariablesMetaRepository[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]              = TestServices.featuresService
  val queryToJobService: QueryToJobService[IO] =
    QueryToJobService[IO](featuresService, variablesMetaService, jobsConf, algorithmLookup)

  val algorithmExecutor: AlgorithmExecutor[IO] = mock[AlgorithmExecutor[IO]]

  lazy val service: LocalExperimentService[IO] =
    LocalExperimentService[IO](algorithmExecutor,
                               TestServices.wokenWorker,
                               featuresService,
                               TestServices.jobResultService,
                               system)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Experiment flow" should {

    "fail in case of a query with no algorithms" in {
      val experimentQuery = ExperimentQuery(
        user = user,
        variables = Nil,
        covariables = Nil,
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = None,
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val job: IO[Validation[ExperimentJobInProgress]] =
        queryToJobService.experimentQuery2Job(experimentQuery)

      job.unsafeRunSync().isValid shouldBe false

    }

    "fail in case of a query containing an invalid algorithm" in {
      val experiment = experimentQuery("invalid-algo", Nil)
      val job: IO[Validation[ExperimentJobInProgress]] =
        queryToJobService.experimentQuery2Job(experiment)

      val experimentJob = job.unsafeRunSync()

      experimentJob.isValid shouldBe false
    }

    "fail in case of a job without algorithm" in {
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("local")
      val experiment = ExperimentQuery(
        user = UserId("test1"),
        variables = List(VariableId("cognitive_task2")),
        covariables = List(VariableId("score_test1"), VariableId("college_math")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithms = List.empty,
        validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        executionPlan = None
      )

      val temp = ExperimentJob(
        jobId = "test1",
        inputTable = featuresTableId,
        query = experiment,
        queryAlgorithms = Map.empty,
        metadata = List.empty
      )

      val jobStarted = JobInProgress[ExperimentQuery, ExperimentJob](
        job = temp,
        dataProvenance = Set.empty,
        feedback = List.empty
      )

      val result = for {
        r <- service.runExperiment(jobStarted)
      } yield r

      val experimentResult = result.attempt.unsafeRunSync()

      experimentResult match {
        case Left(_) =>
          fail("Job should be successful.")

        case Right(response) =>
          response.result match {
            case Left(jobErr) =>
              jobErr.error shouldBe "Experiment contains no algorithms"
            case Right(_) =>
              fail("Experiment execution should fail due to missing algorithm.")
          }
      }

    }

    "complete with an error response in case of a query containing a failing algorithm" in {
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("local")
      val experiment = experimentQuery("knn", List(CodeValue("k", "3")))
      val job: IO[Validation[ExperimentJobInProgress]] =
        queryToJobService.experimentQuery2Job(experiment)

      val result = for {
        j <- job
        r <- service.runExperiment(j.value)
      } yield r

      val experimentResult = result.attempt.unsafeRunSync()

      experimentResult match {
        case Left(err) => fail("Failed to execute algorithm: " + err.toString, err)
        case Right(response) =>
          logger.info(s"Experiment response: ${response.toQueryResult}")
          response.result match {
            case Left(jobErr) =>
              fail("Failed to execute experiment job with specific algorithm: " + jobErr)
            case Right(jobResult) =>
              logger.info(
                s"Job results for experiment of user ${experiment.user} is: $jobResult"
              )
              jobResult.results.isEmpty shouldBe false
          }
      }

    }

    "complete with success in case of a valid query on Anova algorithm (non predictive)" in {

      val experiment = experimentQuery(List(anovaFactorial))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("anova"))

    }

    "complete with failure in case of a runtime exception during experiment execution" in {
      (algorithmExecutor.node _)
        .expects()
        .anyNumberOfTimes()
        .throws(new RuntimeException("Bang !"))
      val experiment = experimentQuery(List(knnWithK5))

      runFailedExperimentTest(experiment)

    }

    // TODO
    "complete with success in case of a valid query on k-NN algorithm (predictive)" ignore {
      val experiment = experimentQuery(List(knnWithK5))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))

    }

    // TODO
    "split flow should return validation failed" ignore {
      val experiment = experimentQuery(List(knnWithK5))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))
    }

    // TODO
    "complete with success in case of valid algorithms" ignore {
      val experiment: ExperimentQuery = experimentQuery(
        List(
          knnWithK5,
          anovaFactorial
        )
      )

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))
    }
  }

  private def runExperimentTest(experimentQuery: ExperimentQuery,
                                algorithmExecutor: AlgorithmExecutor[IO]) = {
    val serviceWithExpectedAlgorithm: LocalExperimentService[IO] =
      LocalExperimentService[IO](algorithmExecutor,
                                 TestServices.wokenWorker,
                                 featuresService,
                                 TestServices.jobResultService,
                                 system)

    val job: IO[Validation[ExperimentJobInProgress]] =
      queryToJobService.experimentQuery2Job(experimentQuery)

    val result = for {
      j <- job
      r <- serviceWithExpectedAlgorithm.runExperiment(j.value)
    } yield r

    val experimentResult: Either[Throwable, LocalExperimentService.LocalExperimentResponse] =
      result.attempt.unsafeRunSync()

    experimentResult match {
      case Left(err) => fail("Failed to execute algorithm: " + err.toString, err)
      case Right(response) =>
        logger.info(s"Experiment response: ${response.toQueryResult}")
        response.result match {
          case Left(jobErr) =>
            fail("Failed to execute experiment job with specific algorithm: " + jobErr)
          case Right(jobResult) =>
            logger.info(
              s"Job results for experiment of user ${experimentQuery.user} is: $jobResult"
            )
            jobResult.results.isEmpty shouldBe false
        }
    }

  }

  private def runFailedExperimentTest(experimentQuery: ExperimentQuery) = {
    val serviceWithExpectedAlgorithm: LocalExperimentService[IO] =
      LocalExperimentService[IO](algorithmExecutor,
                                 TestServices.wokenWorker,
                                 featuresService,
                                 TestServices.jobResultService,
                                 system)

    val job: IO[Validation[ExperimentJobInProgress]] =
      queryToJobService.experimentQuery2Job(experimentQuery)

    val result = for {
      j <- job
      r <- serviceWithExpectedAlgorithm.runExperiment(j.value)
    } yield r

    val experimentResult: Either[Throwable, LocalExperimentService.LocalExperimentResponse] =
      result.attempt.unsafeRunSync()

    experimentResult match {
      case Left(err) =>
        err.getMessage.isEmpty shouldBe false
      case Right(_) =>
        fail("Should fail due to exception passed.")

    }

  }

}
