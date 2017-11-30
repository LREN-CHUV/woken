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
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.WokenConfig
import eu.hbp.mip.woken.core.ExperimentActor.{ ErrorResponse, Start }
import eu.hbp.mip.woken.core.{ CoordinatorConfig, ExperimentActor }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.dao.{ JobResultsDAL, LdsmDAL }
import eu.hbp.mip.woken.messages.external._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import spray.json.JsObject

import scala.concurrent.duration._

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

  case class FakeMiningService(chronosServiceRef: ActorRef,
                               resultDB: JobResultsDAL,
                               ldsmDB: LdsmDAL)
      extends MiningService(chronosServiceRef, resultDB, ldsmDB) {

    override def newExperimentActor(coordinatorConfig: CoordinatorConfig): ActorRef =
      system.actorOf(FakeActor.echoActorProps)

    override def newCoordinatorActor(coordinatorConfig: CoordinatorConfig): ActorRef =
      system.actorOf(FakeActor.echoActorProps)

  }

  case class FakeLdsmDAL()
      extends LdsmDAL(jdbcDriver = "", jdbcUrl = "", jdbcUser = "", jdbcPassword = "", table = "")

  case class FakeApi(implicit val system: ActorSystem) extends Api {
    override implicit def actorRefFactory: ActorRefFactory = system

    override lazy val mining_service: MiningService =
      FakeMiningService(chronosHttp, FakeJobResultsDAL(), FakeLdsmDAL())

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
      system.actorOf(Props(new MasterRouter(api, FakeJobResultsDAL(), query2job, query2job)))

    "starts new experiments" in {

      val messages = WokenConfig.app.masterRouterConfig.experimentActorsLimit
      within(messages seconds) {
        (1 to messages).foreach { _ =>
          router ! ExperimentQuery(variables = Nil,
                                   covariables = Nil,
                                   grouping = Nil,
                                   filters = "",
                                   algorithms = Nil,
                                   validations = Nil)

        }

        (1 to messages).foreach { _ =>
          expectMsgType[Start](5 seconds)
        }

        expectNoMsg()
        Thread.sleep(1000)
      }

    }

    "do not starts new experiments" in {

      val messages = WokenConfig.app.masterRouterConfig.experimentActorsLimit
      within(messages seconds) {
        (1 to (messages + 2)).foreach { _ =>
          router ! ExperimentQuery(variables = Nil,
                                   covariables = Nil,
                                   grouping = Nil,
                                   filters = "",
                                   algorithms = Nil,
                                   validations = Nil)

        }

        expectMsgType[ErrorResponse]
        Thread.sleep(300)
      }

    }

  }
}
