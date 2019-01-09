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

import java.util.UUID

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestKit, TestProbe }
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.Predefined.Algorithms.{
  anovaDefinition,
  anovaFactorial,
  knnDefinition,
  knnWithK5
}
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableId }
import ch.chuv.lren.woken.core.model.jobs.{ ErrorJobResult, JobResult, PfaJobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.{ Dataset, DatasetId }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.service.{ DispatcherService, TestServices }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import cats.implicits._
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Experiment flow should always complete with success, but the error is reported inside the response.
  */
class ExperimentFlowTest
    extends TestKit(ActorSystem("ExperimentFlowTest"))
    with WordSpecLike
    with Matchers
    with ValidatedMatchers
    with ValidatedValues
    with BeforeAndAfterAll
    with JsonUtils
    with LazyLogging {

  val config: Config =
    ConfigFactory
      .parseResourcesAnySyntax("algorithms.conf")
      .withFallback(ConfigFactory.load("test.conf"))
      .resolve()

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val user: UserId                             = UserId("test")

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_test1"), VariableId("college_math")),
      covariablesMustExist = false,
      grouping = Nil,
      filters = None,
      targetTable = Some("Sample"),
      algorithms = List(AlgorithmSpec(algorithm, parameters, None)),
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    ExperimentJob.mkValid(
      UUID.randomUUID().toString,
      query,
      FeaturesTableDescription(TableId("features_db", None, query.targetTable.getOrElse("Sample")),
                               Nil,
                               None,
                               validateSchema = false,
                               None,
                               0.67),
      Nil, { spec =>
        Map(knnWithK5 -> knnDefinition, anovaFactorial -> anovaDefinition)
          .get(spec)
          .toRight("Missing algorithm")
          .toValidatedNel[String]
      }
    )

  import ExperimentFlowWrapper._

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
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val experimentJob = experimentQuery2job(experimentQuery)
      experimentJob.isValid shouldBe false
      experimentJob.invalidValue.head shouldBe "No algorithm defined"
      experimentJob.invalidValue.size shouldBe 1
    }

    "fail in case of a query containing an invalid algorithm" in {
      val experiment    = experimentQuery("invalid-algo", Nil)
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe false
      experimentJob.invalidValue.head shouldBe "Missing algorithm"
      experimentJob.invalidValue.size shouldBe 1
    }

    "complete with an error response in case of a query containing a failing algorithm" ignore {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.propsFailingAlgorithm("Algorithm execution failed"))
      val experiment    = experimentQuery("knn", Nil)
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          print(response)
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("knn", Nil, None)
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error shouldBe "Algorithm execution failed"
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }

    }

    "complete with success in case of a valid query on Anova algorithm (non predictive)" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.propsSuccessfulAlgorithm("anova"))

      val experiment = experimentQuery("anova", List(CodeValue("design", "factorial")))

      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          println(response.result)
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("anova",
                                                         List(CodeValue("design", "factorial")),
                                                         None)
          response.result.head._2 match {
            case pfa: PfaJobResult =>
              pfa.algorithm shouldBe "anova"
              pfa.node shouldBe "testNode"
              pfa.model.compactPrint.nonEmpty shouldBe true
            case _ => fail("Response should be of type ErrorJobResponse")
          }
      }
    }

    "complete with success in case of a valid query on k-NN algorithm (predictive)" ignore {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.propsSuccessfulAlgorithm("knn"))

      val experiment = experimentQuery("knn", List(CodeValue("k", "5")))

      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error.nonEmpty shouldBe true
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }
    }

    /* TODO
    "split flow should return validation failed" ignore {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props, "SplitFlowProbeActor")
      val experimentQuery = ExperimentQuery(
        user = user,
        variables = Nil,
        covariables = Nil,
        grouping = Nil,
        filters = None,
        targetTable = None,
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val experimentJob = experimentQuery2job(experimentQuery)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, SplitFlowCommand(experimentJob.toOption.get))
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: SplitFlowResponse =>
          response.algorithmMaybe.isDefined shouldBe true
      }
    }
   */

  }

  object ExperimentFlowWrapper {

    def propsFailingAlgorithm(errorMsg: String): Props =
      props("", Some(FakeCoordinatorActor.executeFailingJobAsync(errorMsg)))

    def propsSuccessfulAlgorithm(expectedAlgo: String): Props = props(expectedAlgo, None)

    def props(expectedAlgorithm: String, executeJob: Option[CoordinatorActor.ExecuteJobAsync]) =
      Props(new ExperimentFlowWrapper(expectedAlgorithm, executeJob))

    case class ExperimentResponse(result: Map[AlgorithmSpec, JobResult])

    case class SplitFlowCommand(job: ExperimentActor.Job)

    case class SplitFlowResponse(algorithmMaybe: Option[ExperimentFlow.JobForAlgorithmPreparation])

  }

  /**
    * This actor provides the environment for executing experimentFlow
    */
  class ExperimentFlowWrapper(expectedAlgorithm: String,
                              executeJob: Option[CoordinatorActor.ExecuteJobAsync])
      extends Actor {

    private val coordinatorActor =
      context.actorOf(FakeCoordinatorActor.props(expectedAlgorithm, None))
    private val coordinatorConfig  = FakeCoordinatorConfig.coordinatorConfig(coordinatorActor)
    private val wokenClientService = WokenClientService("test")
    private val dispatcherService =
      DispatcherService(Map[DatasetId, Dataset]().validNel[String], wokenClientService)

    val experimentFlow = ExperimentFlow(
      executeJob.getOrElse(
        FakeCoordinatorActor.executeJobAsync(FakeCoordinatorActor.props(expectedAlgorithm, None),
                                             context)
      ),
      FakeCoordinatorConfig.fakeFeaturesTableService,
      coordinatorConfig.jobsConf,
      dispatcherService,
      Nil,
      TestServices.wokenWorker
    )

    override def receive: Receive = {
      case job: ExperimentActor.Job =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.flow)
          .runWith(TestSink.probe[Map[AlgorithmSpec, JobResult]])
          .request(1)
          .receiveWithin(10 seconds, 1)
        //.runWith(TestSink.probe[Map[AlgorithmSpec, JobResult]])
        //request(1)
        //.receiveWithin(10 seconds, 1)

        originator ! ExperimentResponse(result.headOption.getOrElse(Map.empty))

      case SplitFlowCommand(job) =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.splitJob)
          .runWith(TestSink.probe[ExperimentFlow.JobForAlgorithmPreparation])
          .request(1)
          .receiveWithin(10 seconds, 1)
        originator ! SplitFlowResponse(result.headOption)
    }
  }

}
