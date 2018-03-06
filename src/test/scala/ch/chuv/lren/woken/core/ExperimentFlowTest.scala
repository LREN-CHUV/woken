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

package ch.chuv.lren.woken.core

import java.util.UUID

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestKit, TestProbe }
import ch.chuv.lren.woken.config.AlgorithmsConfiguration
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, JobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.util.{ FakeCoordinatorConfig, JsonUtils }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * Experiment flow should always complete with success, but the error is reported inside the response.
  */
class ExperimentFlowTest
    extends TestKit(ActorSystem("ExperimentFlowTest"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with JsonUtils {

  val config: Config                           = ConfigFactory.load("test.conf")
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
      grouping = Nil,
      filters = None,
      targetTable = Some("sample_data"),
      algorithms = List(AlgorithmSpec(algorithm, parameters)),
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    ConfigUtil.lift(
      ExperimentActor.Job(
        jobId = UUID.randomUUID().toString,
        inputDb = "",
        inputTable = "",
        query = query,
        metadata = Nil
      )
    )

  import ExperimentFlowWrapper._

  "Experiment flow" should {

    "complete with success in case of invalid query" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props, "ExperimentFlowProbeActor")
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
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe Nil
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error.nonEmpty shouldBe true
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }

    }

    "complete with success when algorithm is not present " in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props, "ExperimentFlowProbeActor2")

      val experiment = experimentQuery("knn", List(CodeValue("k", "5")))

      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("knn", List(CodeValue("k", "5")))
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error.nonEmpty shouldBe true
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }
    }

    "split flow should return validation failed" in {
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

  }

  class ExperimentFlowWrapper extends Actor {

    val chronosService    = testActor
    val coordinatorConfig = FakeCoordinatorConfig.coordinatorConfig(testActor)
    val algorithmLookup   = AlgorithmsConfiguration.factory(config)
    val experimentFlow    = ExperimentFlow(coordinatorConfig, algorithmLookup, context)

    override def receive: Receive = {
      case job: ExperimentActor.Job =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.flow)
          .runWith(TestSink.probe[Map[AlgorithmSpec, JobResult]])
          .request(1)
          .receiveWithin(10 seconds, 1)
        originator ! ExperimentResponse(result.headOption.getOrElse(Map.empty))

      case SplitFlowCommand(job) =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.splitJob)
          .runWith(TestSink.probe[AlgorithmValidationMaybe])
          .request(1)
          .receiveWithin(10 seconds, 1)
        originator ! SplitFlowResponse(result.headOption)
    }
  }

  object ExperimentFlowWrapper {
    def props = Props(new ExperimentFlowWrapper)

    case class ExperimentResponse(result: Map[AlgorithmSpec, JobResult])

    case class SplitFlowCommand(job: ExperimentActor.Job)

    case class SplitFlowResponse(algorithmMaybe: Option[AlgorithmValidationMaybe])

  }

}
