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

package eu.hbp.mip.woken.backends.chronos

import akka.Done
import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.{ HttpApp, Route }
import akka.http.scaladsl.settings.ServerSettings
import akka.testkit.{ ImplicitSender, TestKit }
import com.typesafe.config.{ Config, ConfigFactory }
import eu.hbp.mip.woken.backends.chronos.ChronosService.Error
import eu.hbp.mip.woken.core.{ Core, CoreActors }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import eu.hbp.mip.woken.backends.chronos.{ EnvironmentVariable => EV, Parameter => P }
import eu.hbp.mip.woken.util.FakeActors

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.language.postfixOps

class ChronosServiceTest
    extends TestKit(ActorSystem("ChronosServiceSpec"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with Core
    with CoreActors {

  import system.dispatcher

  override protected lazy val config: Config = ConfigFactory.load("test.conf")

  class MockChronosServer extends HttpApp {
    val shutdownPromise: Promise[Done]         = Promise[Done]()
    val bindingPromise: Promise[ServerBinding] = Promise[ServerBinding]()

    def shutdownServer(): Unit = shutdownPromise.success(Done)

    override def startServer(host: String, port: Int): Unit =
      startServer(host, port, ServerSettings(config))

    override protected def postHttpBinding(binding: ServerBinding): Unit = {
      super.postHttpBinding(binding)
      bindingPromise.success(binding)
    }

    override protected def waitForShutdownSignal(
        system: ActorSystem
    )(implicit ec: ExecutionContext): Future[Done] =
      shutdownPromise.future

    override protected def routes: Route = pathPrefix("/v1/scheduler/iso8601") {
      post {
        complete(200 -> "")
      }
    }
  }

  override protected def mainRouter: ActorRef = system.actorOf(FakeActors.echoActorProps)

  var mockChronosServer: MockChronosServer = _
  var binding: ServerBinding               = _

  override def beforeAll(): Unit = {
    mockChronosServer = new MockChronosServer()

    Future {
      mockChronosServer.startServer("localhost", 9999)
    }
    binding = Await.result(mockChronosServer.bindingPromise.future, 5 seconds)

    println("Mock Chronos server started...")
  }

  override def afterAll(): Unit = {
    mockChronosServer.shutdownServer()
    TestKit.shutdownActorSystem(system)
  }

  "Chronos Service" must {

    val container = Container(`type` = ContainerType.DOCKER,
                              image = "hbpmip/somealgo",
                              network = NetworkMode.BRIDGE,
                              parameters = List(P("network", "bridge1")))

    val environmentVariables: List[EV] =
      List(EV("JOB_ID", "12345"), EV("NODE", "local"), EV("DOCKER_IMAGE", "hbpmip/somealgo"))

    val job = ChronosJob(
      name = "hbpmip_somealgo_1",
      command = "compute",
      shell = false,
      schedule = "R1//PT24H",
      epsilon = Some("PT5M"),
      runAsUser = Some("root"),
      container = Some(container),
      cpus = Some(0.5),
      mem = Some(512),
      owner = Some("mip@chuv.ch"),
      environmentVariables = environmentVariables,
      retries = 0
    )

    "Schedule a new job" in {
      chronosHttp ! ChronosService.Schedule(job, self)

      within(40 seconds) {
        val msg = expectMsgType[Error](10 seconds)
        println(msg)
      }
    }

  }

}
