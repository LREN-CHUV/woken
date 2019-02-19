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
import org.scalatest.{ BeforeAndAfterAll, EitherValues, Matchers, WordSpecLike }
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
    extends TestKit(ActorSystem("ExperimentFlowTest"))
    with WordSpecLike
    with Matchers
    with ValidatedMatchers
    with ValidatedValues
    with EitherValues
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

    "complete with an error response in case of a query containing a failing algorithm (anova, non predictive)" in {
      val algorithmExecutor = mock[AlgorithmExecutor[IO]]
      (algorithmExecutor.node _).expects().anyNumberOfTimes().returns("testNode")
      (algorithmExecutor.execute _)
        .expects(where { job: DockerJob =>
          job.algorithmDefinition.code == "anova"
        })
        .anyNumberOfTimes()
        .returns(IO.raiseError(new TimeoutException("No response")))

      val experimentResult = runExperimentTest(anovaAlgorithmQuery, algorithmExecutor)

      // TODO: check the error message in the result
      println(experimentResult)
      // Right(LocalExperimentResponse(JobInProgress(ExperimentJob(bbd5343c-0180-49a0-9396-f97dcfa15afc,features_db.public.sample_data,ExperimentQuery(UserId(test1),List(VariableId(cognitive_task2)),List(VariableId(score_test1), VariableId(college_math)),false,List(),Some(CompoundFilterRule(AND,List(SingleFilterRule(cognitive_task2,cognitive_task2,string,text,is_not_null,List()), SingleFilterRule(score_test1,score_test1,string,text,is_not_null,List()), SingleFilterRule(college_math,college_math,string,text,is_not_null,List())))),Some(features_db.public.sample_data),TreeSet(),TreeSet(),List(AlgorithmSpec(anova,List(CodeValue(design,factorial)),None)),TreeSet(),List(ValidationSpec(kfold,List(CodeValue(k,3)))),None),Map(AlgorithmSpec(anova,List(CodeValue(design,factorial)),None) -> AlgorithmDefinition(anova,hbpmip/python-anova:0.4.5,false,false,false,Docker,ExecutionPlan(List(ExecutionStep(scatter,map,SelectDataset(training),Compute(compute)), ExecutionStep(gather,gather,PreviousResults(scatter),Fold))))),List(VariableMetaData(cognitive_task2,Cognitive Task 2,real,None,Some(test),Some(),None,None,None,None,None,None,Set()), VariableMetaData(score_test1,Score Test 1,real,None,Some(test),Some(),None,None,None,None,None,None,Set()), VariableMetaData(college_math,College Math,real,None,Some(test),Some(),None,None,None,None,None,None,Set()))),Set(),List()),Right(ExperimentJobResult(bbd5343c-0180-49a0-9396-f97dcfa15afc,testNode,Map(AlgorithmSpec(anova,List(CodeValue(design,factorial)),None) -> ErrorJobResult(Some(efc518ee-e6c5-4c49-949c-37f7dfddfd74),testNode,2019-02-19T19:25:17.872085+01:00,Some(anova),No response)),2019-02-19T19:25:17.876077+01:00))))
      experimentResult.isRight shouldBe true
      val response = experimentResult.right.value.result
      response.isRight shouldBe true
      val resultsPerAlgorithm = response.right.value.results
      resultsPerAlgorithm.isEmpty shouldBe false
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
        .once()
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
                                 TestServices.jobResultService,
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
                               TestServices.jobResultService,
                               system)

}
