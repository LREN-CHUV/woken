/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestKit }
import eu.hbp.mip.woken.api.MasterRouter.{ QueuesSize, RequestQueuesSize }
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config._
import eu.hbp.mip.woken.core.ExperimentActor.Start
import eu.hbp.mip.woken.core.{
  CoordinatorConfig,
  ExperimentActor,
  FakeCoordinatorActor,
  FakeExperimentActor
}
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.messages.external._
import com.typesafe.config.{ Config, ConfigFactory }
import eu.hbp.mip.woken.service.AlgorithmLibraryService
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import spray.json.JsObject
import cats.data.Validated._
import eu.hbp.mip.woken.core.model.ErrorJobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Validates correct implementation of the MasterRouter.
  */
class MasterRouterTest
    extends TestKit(ActorSystem("MasterActorSpec"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  import eu.hbp.mip.woken.service.TestServices._

  val noDbConfig =
    DatabaseConfiguration(jdbcDriver = "java.lang.String", jdbcUrl = "", user = "", password = "")
  val noJobsConf = JobsConfiguration("none", "noone", "http://nowhere", "features", "results")

  val fakeFeaturesDAL = FeaturesDAL(noDbConfig)

  def experimentQuery2job(query: ExperimentQuery) = ExperimentActor.Job(
    jobId = UUID.randomUUID().toString,
    inputDb = "",
    inputTable = "",
    query = query,
    metadata = JsObject.empty
  )

  def miningQuery2job(query: MiningQuery): DockerJob = DockerJob(
    jobId = UUID.randomUUID().toString,
    dockerImage = "",
    inputDb = "",
    inputTable = "",
    query = query,
    metadata = JsObject.empty
  )

  class MasterRouterUnderTest(coordinatorConfig: CoordinatorConfig,
                              algorithmLibraryService: AlgorithmLibraryService)
      extends MasterRouter(coordinatorConfig,
                           algorithmLibraryService,
                           experimentQuery2job,
                           miningQuery2job) {

    override def newExperimentActor: ActorRef =
      system.actorOf(Props(new FakeExperimentActor()))

    override def newCoordinatorActor: ActorRef =
      system.actorOf(Props(new FakeCoordinatorActor()))

  }

  val config: Config                                                      = ConfigFactory.load()
  val jdbcConfigs: String => ConfigUtil.Validation[DatabaseConfiguration] = _ => Valid(noDbConfig)

  val coordinatorConfig: CoordinatorConfig = CoordinatorConfig(
    system.actorOf(FakeActor.echoActorProps),
    None,
    fakeFeaturesDAL,
    jobResultService,
    noJobsConf,
    jdbcConfigs.apply
  )

  "Master Actor " must {

    val router =
      system.actorOf(
        Props(new MasterRouterUnderTest(coordinatorConfig, algorithmLibraryService))
      )

    "starts new experiments" in {

      val limit = WokenConfig.app.masterRouterConfig.experimentActorsLimit

      (1 to limit).foreach { _ =>
        router ! ExperimentQuery(variables = Nil,
                                 covariables = Nil,
                                 grouping = Nil,
                                 filters = "",
                                 algorithms = Nil,
                                 validations = Nil)

      }

      within(limit seconds) {

        (1 to limit).foreach { _ =>
          expectMsgType[QueryResult](5 seconds)
        }

        expectNoMsg()
      }

      waitForEmptyQueue(router, limit)
    }

    "not start new experiments over the limit of concurrent experiments, then recover" in {

      val limit    = WokenConfig.app.masterRouterConfig.experimentActorsLimit
      val overflow = limit * 2

      (1 to overflow).foreach { _ =>
        router ! ExperimentQuery(variables = Nil,
                                 covariables = Nil,
                                 grouping = Nil,
                                 filters = "",
                                 algorithms = Nil,
                                 validations = Nil)

      }

      var successfulStarts = 0
      var failures         = 0

      within(overflow seconds) {

        (1 to overflow).foreach { i =>
          expectMsgPF[Unit](5 seconds) {
            case QueryResult(_, _, _, _, _, Some(_), None) => successfulStarts += 1
            case QueryResult(_, _, _, _, _, None, Some(_)) => failures += 1
          }
        }

        expectNoMsg()
        Thread.sleep(100)
      }

      assert(failures > 0)

      (successfulStarts + failures) shouldBe overflow

      waitForEmptyQueue(router, overflow)

      // Now we check that after rate limits, the actor can recover and handle new requests

      (1 to limit).foreach { _ =>
        router ! ExperimentQuery(variables = Nil,
                                 covariables = Nil,
                                 grouping = Nil,
                                 filters = "",
                                 algorithms = Nil,
                                 validations = Nil)

      }

      within(limit seconds) {

        (1 to limit).foreach { _ =>
          expectMsgType[QueryResult](5 seconds)
        }

        expectNoMsg()
      }

      waitForEmptyQueue(router, limit)

    }

  }

  private def waitForEmptyQueue(router: ActorRef, limit: Int): Unit =
    awaitAssert({
      router ! RequestQueuesSize
      val queues = expectMsgType[QueuesSize](5 seconds)
      println(queues)
      queues.isEmpty shouldBe true

    }, max = limit seconds, interval = 200.millis)

}
