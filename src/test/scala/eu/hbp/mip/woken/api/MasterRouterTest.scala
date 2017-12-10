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

import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestKit }
import eu.hbp.mip.woken.api.MasterRouter.{ QueuesSize, RequestQueuesSize }
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config._
import eu.hbp.mip.woken.core.ExperimentActor.{ ErrorResponse, Start }
import eu.hbp.mip.woken.core.{ CoordinatorConfig, ExperimentActor }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.dao.{ FeaturesDAL, JobResultsDAL }
import eu.hbp.mip.woken.messages.external._
import FunctionsInOut._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import spray.json.JsObject

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

  case class FakeJobResultsDAL(jobResults: List[JobResult] = List.empty) extends JobResultsDAL {
    override def findJobResults(jobId: String): List[JobResult] = jobResults
  }

  val noDbConfig = DatabaseConfiguration(jdbcDriver = "java.lang.String",
                                             jdbcUrl = "",
                                             jdbcUser = "",
                                             jdbcPassword = "")
  val noJobsConf = JobsConfiguration("none", "noone", "http://nowhere", "features", "results")

  val fakeFeaturesDAL = FeaturesDAL(noDbConfig)
  val fakeResultsDAL  = FakeJobResultsDAL()
  val fakeMetaDbConfig = new MetaDatabaseConfig(noDbConfig) {
    override def testConnection(jdbcUrl: String): Unit = ()
  }

  case class FakeMiningService(chronosServiceRef: ActorRef,
                               resultDB: JobResultsDAL,
                               featuresDB: FeaturesDAL)
      extends MiningService(chronosServiceRef,
                            fakeFeaturesDAL,
                            fakeResultsDAL,
                            fakeMetaDbConfig,
                            noJobsConf,
                            "") {

    override def newExperimentActor(coordinatorConfig: CoordinatorConfig): ActorRef =
      system.actorOf(FakeActor.echoActorProps)

    override def newCoordinatorActor(coordinatorConfig: CoordinatorConfig): ActorRef =
      system.actorOf(FakeActor.echoActorProps)

  }

  case class FakeApi(implicit val system: ActorSystem) extends Api {
    override implicit def actorRefFactory: ActorRefFactory = system

    override lazy val mining_service: MiningService =
      FakeMiningService(chronosHttp, fakeResultsDAL, fakeFeaturesDAL)

    override def config: Config = ConfigFactory.load()

    override def featuresDAL: FeaturesDAL = fakeFeaturesDAL

    override def jobResultService: JobResultsDAL = fakeResultsDAL

    override def metaDbConfig: MetaDatabaseConfig = fakeMetaDbConfig
  }

  def query2job(query: ExperimentQuery) = ExperimentActor.Job(
    jobId = UUID.randomUUID().toString,
    inputDb = "",
    inputTable = "",
    query = query,
    metadata = JsObject.empty
  )

  def query2job(query: MiningQuery): DockerJob = DockerJob(
    jobId = UUID.randomUUID().toString,
    dockerImage = "",
    inputDb = "",
    inputTable = "",
    query = query,
    metadata = JsObject.empty
  )

  "Master Actor " must {

    val api = FakeApi()

    val router =
      system.actorOf(
        Props(
          new MasterRouter(api,
                           fakeFeaturesDAL,
                           fakeResultsDAL,
                           experimentQuery2job(fakeMetaDbConfig),
                           miningQuery2job(fakeMetaDbConfig))
        )
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
          expectMsgType[Start](5 seconds)
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
            case _: Start         => successfulStarts += 1
            case _: ErrorResponse => failures += 1
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
          expectMsgType[Start](5 seconds)
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
