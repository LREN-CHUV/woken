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

package ch.chuv.lren.woken.akka

import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.testkit.{ ImplicitSender, TestKit }
import ch.chuv.lren.woken.config._
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service._
import ch.chuv.lren.woken.messages.datasets._
import ch.chuv.lren.woken.messages.datasets.AnonymisationLevel._
import ch.chuv.lren.woken.messages.variables.VariableId
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import org.scalatest.tagobjects.Slow
import cats.effect.{ Effect, IO }
import cats.syntax.validated._
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, ExperimentJob }
import ch.chuv.lren.woken.dispatch.DispatchActors
import ch.chuv.lren.woken.messages.remoting.RemoteLocation

import scala.collection.immutable.TreeSet
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }

/**
  * Validates correct implementation of the MasterRouter.
  */
class MasterRouterTest
    extends TestKit(ActorSystem("MasterActorSpec", centralNodeConfigSource))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentJob] =
    ExperimentJob(
      jobId = UUID.randomUUID().toString,
      inputTable = featuresTableId,
      query = query,
      metadata = Nil,
      queryAlgorithms = Map()
    ).validNel[String]

  def miningQuery2job(query: MiningQuery): Validation[DockerJob] = {
    val featuresQuery = query
      .filterNulls(variablesCanBeNull = true, covariablesCanBeNull = true)
      .features(featuresTableId, None)

    DockerJob(
      jobId = UUID.randomUUID().toString,
      query = featuresQuery,
      algorithmSpec = query.algorithm,
      algorithmDefinition = config
        .algorithmLookup(query.algorithm.code)
        .getOrElse(throw new IllegalArgumentException("Unknown algorithm")),
      metadata = Nil
    ).validNel[String]
  }

  class MasterRouterUnderTest[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F],
      override val dispatchActors: DispatchActors
  ) extends MasterRouter(config, databaseServices, backendServices, dispatchActors) {

//    override def newExperimentActor: ActorRef =
//      system.actorOf(Props(new FakeExperimentActor()))
//
//    override def newCoordinatorActor: ActorRef =
//      system.actorOf(FakeCoordinatorActor.props("knn", None))

  }

  class RouterWithProbeCoordinator[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F],
      dispatchActors: DispatchActors
  ) extends MasterRouterUnderTest(config, databaseServices, backendServices, dispatchActors) {

    //override def newCoordinatorActor: ActorRef = coordinatorActor

  }

  val config: WokenConfiguration = centralNodeConfig

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val wokenService: WokenClientService = WokenClientService("test")

  val databaseServices: DatabaseServices[IO] = TestServices.databaseServices(config)

  val echoActor: ActorRef = system.actorOf(FakeActors.echoActorProps)

  val dispatcherService: DispatcherService =
    DispatcherService(databaseServices.datasetService, wokenService)

  val backendServices: BackendServices[IO] = TestServices.backendServices(system)

  val dispatchActors = new DispatchActors(echoActor, echoActor, echoActor)

  val user: UserId = UserId("test")

  "Master actor" must {

    val router =
      system.actorOf(
        Props(
          new MasterRouterUnderTest(config, databaseServices, backendServices, dispatchActors)
        )
      )

    "fail starting a new mining job" ignore {

      val errorMessage = "Fake error message"

      val miningRouter = system.actorOf(
        Props(
          new RouterWithProbeCoordinator(config, databaseServices, backendServices, dispatchActors)
        )
      )

      miningRouter !
      MiningQuery(
        user = UserId("test1"),
        variables = List(VariableId("cognitive_task2")),
        covariables = List(VariableId("score_math_course1")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
        datasets = TreeSet(),
        executionPlan = None
      )

      expectMsgPF(10 seconds, "error message") {
        case QueryResult(_, _, _, _, _, _, _, _, Some(error), _) =>
          error shouldBe errorMessage
        case msg =>
          fail(s"received unexpected message $msg")
      }

    }

    "return available datasets" in {

      router ! DatasetsQuery(Some(cdeFeaturesATableId.name))

      within(5 seconds) {
        val msg = expectMsgType[DatasetsResponse]
        val expected = Set(
          Dataset(
            DatasetId("remoteData1"),
            "Remote dataset #1",
            "Remote dataset #1",
            List(cdeFeaturesATableId),
            Depersonalised,
            Some(RemoteLocation("http://service.remote/1", None))
          ),
          Dataset(
            DatasetId("remoteData2"),
            "Remote dataset #2",
            "Remote dataset #2",
            List(cdeFeaturesATableId),
            Depersonalised,
            Some(RemoteLocation("http://service.remote/2", None))
          ),
          Dataset(
            DatasetId("remoteData3"),
            "Remote dataset #3",
            "Remote dataset #3",
            List(cdeFeaturesATableId),
            Depersonalised,
            Some(RemoteLocation("wss://service.remote/3", None))
          )
        )

        msg.datasets shouldBe expected
      }
    }

  }

}
