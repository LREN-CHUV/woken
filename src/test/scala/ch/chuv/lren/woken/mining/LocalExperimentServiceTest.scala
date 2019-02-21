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

import java.util.concurrent.TimeoutException

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
import org.scalatest._
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.backends.faas.{
  AlgorithmExecutor,
  AlgorithmExecutorInstances,
  AlgorithmResults
}

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import ch.chuv.lren.woken.config.AlgorithmsConfiguration
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.dao.VariablesMetaRepository
import ch.chuv.lren.woken.Predefined.ExperimentQueries._
import ch.chuv.lren.woken.Predefined.Jobs._
import ch.chuv.lren.woken.config.ConfigurationInstances._
import org.scalamock.scalatest.MockFactory

/**
  * Experiment flow should always complete with success, but the error is reported inside the response.
  */
class LocalExperimentServiceTest
    extends TestKit(ActorSystem("LocalExperimentServiceTest", localNodeConfigSource))
    with WordSpecLike
    with Matchers
    with ValidatedMatchers
    with ValidatedValues
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll
    with MockFactory
    with JsonUtils
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = ExecutionContext.global

  val algorithmLookup: String => Validation[AlgorithmDefinition] =
    AlgorithmsConfiguration.factory(localNodeConfigSource)

  val variablesMetaService: VariablesMetaRepository[IO] = TestServices.localVariablesMetaService
  val featuresService: FeaturesService[IO]              = TestServices.featuresService
  val queryToJobService: QueryToJobService[IO] =
    QueryToJobService[IO](featuresService, variablesMetaService, jobsConf, algorithmLookup)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Experiment flow" should {

    "complete with an error response when executing a failing algorithm (anova, non predictive)" in {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "anova"
        })
        .anyNumberOfTimes()
        .returns(IO.raiseError(new TimeoutException("Oh noes, she did not call me back!")))

      val experimentResult = runExperimentTest(anovaAlgorithmQuery, algorithmExecutor)

      val resultsPerAlgorithm = expectSuccessfulExperiment(experimentResult)

      resultsPerAlgorithm.size shouldBe 1
      val anovaResult = resultsPerAlgorithm.get(anovaFactorial).value
      anovaResult shouldBe an[ErrorJobResult]
      anovaResult should have(
        'error ("Oh noes, she did not call me back!")
      )
    }

    "complete with an error response when executing a failing algorithm (knn, predictive)" in {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "knn"
        })
        .anyNumberOfTimes()
        .returns(IO.raiseError(new TimeoutException("Oh noes, she did not call me back!")))

      val experimentResult = runExperimentTest(knnAlgorithmCdeQuery, algorithmExecutor)

      val resultsPerAlgorithm = expectSuccessfulExperiment(experimentResult)

      resultsPerAlgorithm.size shouldBe 1
      val knnResult = resultsPerAlgorithm.get(knnWithK5).value
      knnResult shouldBe an[ErrorJobResult]
      knnResult should have(
        'error ("Oh noes, she did not call me back!")
      )
    }

    "complete with success in case of a valid query on Anova algorithm (non predictive)" in {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "anova"
        })
        .once()
        .returns(IO.delay(AlgorithmResults(anovaDockerJob, List(anovaJobResult))))

      val resp = runExperimentTest(anovaAlgorithmQuery, algorithmExecutor)

      expectSuccessfulExperiment(resp)

    }

    "complete with failure in case of a runtime exception during experiment execution" ignore {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "anova"
        })
        .anyNumberOfTimes()
        .returns(IO.raiseError(new RuntimeException("Bang!")))

      val response = runExperimentTest(anovaAlgorithmQuery, algorithmExecutor)

      expectFailedExperiment(response, "Bang!")
    }

    "complete with success in case of a valid query on k-NN algorithm (predictive)" in {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "knn"
        })
        .repeat(count = 4) // 3 folds + 1 full training
        .returns(IO.delay(AlgorithmResults(knnDockerJob, List(knnJobResult))))

      val resp = runExperimentTest(knnAlgorithmCdeQuery, algorithmExecutor)

      expectSuccessfulExperiment(resp)

      // TODO: check that cross validation was executed, mock its services as required
    }

    // TODO
    "split flow should return validation failed" ignore {
      val experiment = sampleExperimentQuery(List(knnWithK5))

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))
    }

    // TODO
    "execute an experiment containing several algorithms" ignore {
      val experiment: ExperimentQuery = sampleExperimentQuery(
        List(
          knnWithK5,
          anovaFactorial
        )
      )

      runExperimentTest(experiment, AlgorithmExecutorInstances.expectedAlgorithm("knn"))
    }
  }

  private def runExperimentTest(
      experimentQuery: ExperimentQuery,
      algorithmExecutor: AlgorithmExecutor[IO]
  ): Either[Throwable, LocalExperimentService.LocalExperimentResponse] = {
    val serviceWithExpectedAlgorithm: LocalExperimentService[IO] =
      LocalExperimentService[IO](algorithmExecutor,
                                 TestServices.wokenWorker,
                                 featuresService,
                                 TestServices.wokenRepository,
                                 system)

    val job: IO[Validation[ExperimentJobInProgress]] =
      queryToJobService.experimentQuery2Job(experimentQuery)

    val result = for {
      j <- job
      r <- serviceWithExpectedAlgorithm.runExperiment(j.value)
    } yield r

    result.attempt.unsafeRunSync()
  }

  private def expectSuccessfulExperiment(
      experimentResponse: Either[Throwable, LocalExperimentService.LocalExperimentResponse]
  ): Map[AlgorithmSpec, JobResult] = {
    experimentResponse.isRight shouldBe true
    val response = experimentResponse.right.value.result
    response.isRight shouldBe true
    val jobResult = response.right.value
    jobResult.node shouldBe "testNode"

    val resultsPerAlgorithm = jobResult.results
    resultsPerAlgorithm.isEmpty shouldBe false

    resultsPerAlgorithm
  }

  private def expectFailedExperiment(
      experimentResponse: Either[Throwable, LocalExperimentService.LocalExperimentResponse],
      failureMsg: String
  ): Unit = {
    experimentResponse.isLeft shouldBe true
    val errorMessage = experimentResponse.left.value.getMessage
    errorMessage shouldBe failureMsg
  }

  private def service(algorithmExecutor: AlgorithmExecutor[IO]): LocalExperimentService[IO] =
    LocalExperimentService[IO](algorithmExecutor,
                               TestServices.wokenWorker,
                               featuresService,
                               TestServices.wokenRepository,
                               system)

}
