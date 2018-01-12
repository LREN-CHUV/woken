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

package eu.hbp.mip.woken.service

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import eu.hbp.mip.woken.backends.woken.WokenService
import eu.hbp.mip.woken.config.{ BasicAuthentication, DatasetsConfiguration, RemoteLocation }
import eu.hbp.mip.woken.messages.external.DatasetId
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class DispatcherServiceTest
    extends TestKit(ActorSystem("DispatcherServiceSpec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  implicit val mat: ActorMaterializer = ActorMaterializer()

  val config: Config = ConfigFactory.load("test.conf")

  val wokenService = WokenService("test")

  val dispatcherService: DispatcherService =
    DispatcherService(DatasetsConfiguration.datasets(config), wokenService)

  "Dispatcher service" should {
    "identify locations to dispatch to from a list of datasets" in {

      val datasets =
        Set("sample", "churn", "remoteData1", "remoteData2", "remoteData3").map(DatasetId)

      val (remotes, local) = dispatcherService.dispatchTo(datasets)

      val expectedRemotes = Set(
        RemoteLocation("http://service.remote/1", None),
        RemoteLocation("http://service.remote/2", Some(BasicAuthentication("woken", "wokenpwd"))),
        RemoteLocation("wss://service.remote/3", Some(BasicAuthentication("woken", "wokenpwd")))
      )

      local shouldBe true

      remotes shouldBe expectedRemotes

    }

  }

}
