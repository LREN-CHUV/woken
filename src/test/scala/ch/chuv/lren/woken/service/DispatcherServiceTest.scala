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

package ch.chuv.lren.woken.service

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import ch.chuv.lren.woken.backends.woken.WokenService
import ch.chuv.lren.woken.config.DatasetsConfiguration
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.remoting.{ BasicAuthentication, RemoteLocation }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

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
