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

import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import akka.testkit.{ ImplicitSender, TestKit }
import eu.hbp.mip.woken.config.WokenConfig
import eu.hbp.mip.woken.core.ExperimentActor
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.dao.{ JobResultsDAL, LdsmDAL }
import eu.hbp.mip.woken.messages.external.{ Algorithm, ExperimentQuery, Validation, VariableId }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

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
      extends MiningService(chronosServiceRef, resultDB, ldsmDB)

  case class FakeLdsmDAL()
      extends LdsmDAL(jdbcDriver = "", jdbcUrl = "", jdbcUser = "", jdbcPassword = "", table = "")

  case class FakeApi(implicit val system: ActorSystem) extends Api {
    override implicit def actorRefFactory: ActorRefFactory = system

    override lazy val mining_service: MiningService =
      FakeMiningService(chronosHttp, FakeJobResultsDAL(), FakeLdsmDAL())

  }

  "Master Actor " must {

    "starts new experiments" in {
      val api = FakeApi()
      val router =
        system.actorOf(MasterRouter.props(api, FakeJobResultsDAL()))

      (1 to WokenConfig.app.masterRouterConfig.experimentActorsLimit).foreach { _ =>
        router ! ExperimentQuery(variables = Seq.empty,
                                 covariables = Seq.empty,
                                 grouping = Seq.empty,
                                 filters = "",
                                 algorithms = Seq.empty,
                                 validations = Seq.empty)
      }

      expectMsg(ExperimentActor.ErrorResponse)

    }

  }
}
