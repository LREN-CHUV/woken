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

package ch.chuv.lren.woken.dispatch

import akka.actor.{ Actor, ActorRef, ActorSystem }
import akka.stream.ActorMaterializer
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import akka.util.Timeout
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.dispatch.MetadataQueriesActor.VariablesForDatasets
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}
import ch.chuv.lren.woken.service.{ ConfBasedDatasetService, DatasetService, DispatcherService }
import ch.chuv.lren.woken.service.TestServices.localVariablesMetaService
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.duration._
import scala.language.postfixOps

class MetadataQueriesActorTest
    extends TestKit(ActorSystem("MetadataQueriesActorSpec", localNodeConfigSource))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with LazyLogging {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val timeout: Timeout                = Timeout(10 seconds)
  val testProbe                                = TestProbe()

  "Metadata queries actor in local mode" must {

    val queriesActor = initMetadataQueriesActor(local = true)

    "return all variables if no datasets specified" in {

      val query = VariablesForDatasetsQuery(Set(), exhaustive = false)

      testProbe.send(queriesActor, VariablesForDatasets(query, Actor.noSender))
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: VariablesForDatasetsResponse =>
          logger.debug(s"${response.variables.toList.sortBy(_.code).take(20)}")
          response.variables should have size 199
      }
    }

    "return only variables for datasets if a set is passed with the query" in {

      val churnDatasets = Set(DatasetId("churn"))
      val query         = VariablesForDatasetsQuery(churnDatasets, exhaustive = false)

      testProbe.send(queriesActor, VariablesForDatasets(query, Actor.noSender))
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: VariablesForDatasetsResponse =>
          logger.debug(s"${response.variables.toList.sortBy(_.code).take(20)}")
          response.variables should have size 21
          response.variables.filter(_.datasets == churnDatasets) should have size 21
      }
    }

    "return only variables present in all datasets if exhaustive mode set to true" in {

      val query = VariablesForDatasetsQuery(Set(), exhaustive = true)

      testProbe.send(queriesActor, VariablesForDatasets(query, Actor.noSender))
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: VariablesForDatasetsResponse =>
          logger.debug(s"${response.variables.toList.sortBy(_.code).take(20)}")
          response.variables should have size 0
      }
    }
  }

  private def initMetadataQueriesActor(local: Boolean): ActorRef = {
    val config: Config = if (local) localNodeConfigSource else centralNodeConfigSource

    val wokenService: WokenClientService = WokenClientService("test")

    val datasetService: DatasetService =
      ConfBasedDatasetService(config, WokenConfiguration(config).jobs)

    val dispatcherService: DispatcherService = DispatcherService(datasetService, wokenService)

    system.actorOf(
      MetadataQueriesActor.props(dispatcherService, datasetService, localVariablesMetaService)
    )
  }

}
