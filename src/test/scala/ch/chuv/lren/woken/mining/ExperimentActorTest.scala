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

import akka.actor.{ ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.testkit.{ TestKit, TestProbe }
import cats.implicits._
import cats.effect.IO
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.messages.datasets.{ Dataset, DatasetId }
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, CodeValue, UserId }
import ch.chuv.lren.woken.service.{ DispatcherService, TestServices }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import ExperimentQuerySupport._
import ch.chuv.lren.woken.mining.ExperimentActor.{ Response, StartExperimentJob }

import scala.concurrent.duration._
import scala.language.postfixOps

class ExperimentActorTest
    extends TestKit(ActorSystem("ExperimentActorTest"))
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

  "Experiment actor" should {

    "run an experiment with knn algorithm" in {

      val experimentActor = ExperimentActorWrapper.experimentActor

      val experiment = experimentQuery(
        List(
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None)
        )
      )
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true

      val testProbe = TestProbe()
      testProbe.send(experimentActor,
                     StartExperimentJob(experimentJob.toOption.get, testProbe.ref, testProbe.ref))
      testProbe.expectMsgPF(20 seconds, "error") {
        case Response(_, result, _) if result.isLeft =>
          logger.info(result.left.get.error)
          fail("Knn algorithm experiment failed.")

        case Response(job, result, initiator) =>
          result.isRight shouldBe true
          initiator shouldBe testProbe.ref
        case msg =>
          println(msg)
          fail("Failed test")
      }
    }

    "run an experiment with multiple algorithms" in {

      val experimentActor = ExperimentActorWrapper.experimentActor

      val experiment = experimentQuery(
        List(
          AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
          AlgorithmSpec("anova", List(CodeValue("design", "factorial")), None)
        )
      )
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true

      val testProbe = TestProbe()
      testProbe.send(experimentActor,
                     StartExperimentJob(experimentJob.toOption.get, testProbe.ref, testProbe.ref))
      testProbe.expectMsgPF(20 seconds, "error") {
        case Response(_, result, _) if result.isLeft =>
          logger.info(result.left.get.error)
          fail("Multi algorithm experiment failed.")

        case Response(job, result, initiator) =>
          result.isRight shouldBe true
          initiator shouldBe testProbe.ref
        case msg =>
          println(msg)
          fail("Failed test")
      }
    }

  }

  import ch.chuv.lren.woken.backends.faas.AlgorithmExecutorInstances._

  object ExperimentActorWrapper {

    private val algorithExecutor   = dummyAlgorithm
    private val wokenClientService = WokenClientService("test")
    private val dispatcherService =
      DispatcherService(Map[DatasetId, Dataset]().validNel[String], wokenClientService)

    def experimentActor = system.actorOf(
      Props(
        new ExperimentActor[IO](
          algorithmExecutor = algorithExecutor,
          dispatcherService = dispatcherService,
          featuresService = TestServices.featuresService,
          jobResultService = TestServices.jobResultService,
          wokenWorker = TestServices.wokenWorker
        )
      )
    )

  }

}
