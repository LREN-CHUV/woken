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
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{ FeaturesService, QueryToJobService, TestServices }
import com.typesafe.config.{ Config, ConfigFactory }
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
    with JsonUtils
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = ExecutionContext.global

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

  val variablesMetaService: VariablesMetaRepository[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]              = TestServices.featuresService
  val queryToJobService: QueryToJobService[IO] =
    QueryToJobService[IO](featuresService, variablesMetaService, jobsConf, algorithmLookup)

  lazy val service: LocalExperimentService[IO] =
    LocalExperimentService[IO](TestServices.algorithmExecutor,
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

    "complete with an error response in case of a query containing a failing algorithm" in {
      val experiment = experimentQuery("knn", List(CodeValue("k", "5")))
      val job: IO[Validation[ExperimentJobInProgress]] =
        queryToJobService.experimentQuery2Job(experiment)

      val result = for {
        j <- job
        r <- service.runExperiment(j.value)
      } yield r

      result.attempt.unsafeRunSync().isLeft shouldBe true

    }

    "complete with success in case of a valid query on Anova algorithm (non predictive)" in {

      val experiment = experimentQuery(List(anovaFactorial))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("anova"))

    }

    "complete with success in case of a valid query on k-NN algorithm (predictive)" in {
      val experiment = experimentQuery(List(knnWithK5))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))

    }

    "split flow should return validation failed" in {
      val experiment = experimentQuery(List(knnWithK5))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))
    }

    "complete with success in case of valid algorithms" in {
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
      case Left(err) => fail("Failed to execute algorithm", err)
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

}
