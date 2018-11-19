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

package ch.chuv.lren.woken.backends.chronos

import akka.Done
import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.{ Directives, HttpApp, Route }
import akka.http.scaladsl.settings.ServerSettings
import akka.testkit.{ ImplicitSender, TestKit, TestKitBase }
import com.typesafe.config.ConfigFactory
import ch.chuv.lren.woken.backends.chronos.ChronosService.Ok
import ch.chuv.lren.woken.core.Core
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec, WordSpecLike }
import ch.chuv.lren.woken.backends.chronos.{ EnvironmentVariable => EV, Parameter => P }
import ch.chuv.lren.woken.util.FakeActors

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.language.postfixOps
import ChronosJob._
import akka.cluster.Cluster
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import ch.chuv.lren.woken.akka.CoreActors
import ch.chuv.lren.woken.config.WokenConfiguration
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol

class ChronosServiceTest
    extends WordSpec
    with TestKitBase
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with Core
    with CoreActors
    with DefaultJsonProtocol
    with SprayJsonSupport
    with LazyLogging {

  override protected lazy val config: WokenConfiguration = WokenConfiguration(
    ConfigFactory.load("test.conf")
  )

  /**
    * Construct the ActorSystem we will use in our application
    */
  override lazy implicit val system: ActorSystem = ActorSystem("ChronosServiceSpec", config.config)

  override def cluster: Cluster = null

  class MockChronosServer extends HttpApp with Directives {
    val shutdownPromise: Promise[Done]         = Promise[Done]()
    val bindingPromise: Promise[ServerBinding] = Promise[ServerBinding]()

    def shutdownServer(): Unit = shutdownPromise.success(Done)

    override def startServer(host: String, port: Int): Unit =
      startServer(host, port, ServerSettings(config.config))

    override protected def postHttpBinding(binding: ServerBinding): Unit = {
      super.postHttpBinding(binding)
      bindingPromise.success(binding)
    }

    override protected def waitForShutdownSignal(
        system: ActorSystem
    )(implicit ec: ExecutionContext): Future[Done] =
      shutdownPromise.future

    override protected def routes: Route = pathPrefix("v1" / "scheduler" / "iso8601") {
      post {
        entity(as[ChronosJob]) { job =>
          job.name shouldBe "hbpmip_somealgo_1"
          complete(200 -> "")
        }
      }
    }
  }

  override def mainRouter: ActorRef = system.actorOf(FakeActors.echoActorProps)

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
        val _ = expectMsgType[Ok.type](10 seconds)
      }
    }

  }

}
