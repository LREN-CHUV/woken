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

package ch.chuv.lren.woken.core.validation

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestKit
import ch.chuv.lren.woken.core.FakeCoordinatorActor
import ch.chuv.lren.woken.util.{ FakeCoordinatorConfig, JsonUtils }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.ExecutionContext.Implicits.global

class CrossValidationFlowTest
    extends TestKit(ActorSystem("CrossValidationFlowSpec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with JsonUtils {

  val config: Config                           = ConfigFactory.load("test.conf")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "CrossValidationFlow" should {

    "complete with success if cross validation failed" in {
      val probe = system.actorOf(WrapperActor.props, "crossValidationWrapper")
      probe ! CrossValidationFlow.Job("test-1", "", "", ???, List.empty, ???, ???)
    }

  }

  class WrapperActor extends Actor {
    val chronosService    = testActor
    val coordinatorConfig = FakeCoordinatorConfig.coordinatorConfig(testActor)

    val crossValidationFlow = CrossValidationFlow(
      FakeCoordinatorActor.executeJobAsync(coordinatorConfig, context),
      coordinatorConfig.featuresDatabase,
      context
    )

    override def receive: Receive = {
      case job: CrossValidationFlow.Job =>
        val replyTo = sender()
        val result =
          Source.single(job).via(crossValidationFlow.crossValidate(1)).runWith(Sink.ignore)
        replyTo ! result
    }
  }

  object WrapperActor {
    def props = Props(new WrapperActor)
  }

}
