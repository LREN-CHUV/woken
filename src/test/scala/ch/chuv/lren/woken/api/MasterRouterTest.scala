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

package ch.chuv.lren.woken.api

import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.testkit.{ ImplicitSender, TestKit }
import alleycats.syntax.empty.EmptyOps
import com.typesafe.config.{ Config, ConfigFactory }
import ch.chuv.lren.woken.api.MasterRouter.{ QueuesSize, RequestQueuesSize }
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.config._
import ch.chuv.lren.woken.core.{
  CoordinatorConfig,
  ExperimentActor,
  FakeCoordinatorActor,
  FakeExperimentActor
}
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.service.{
  AlgorithmLibraryService,
  ConfBasedDatasetService,
  DatasetService,
  DispatcherService
}
import ch.chuv.lren.woken.cromwell.core.ConfigUtil
import ch.chuv.lren.woken.backends.woken.WokenService
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.util.FakeActors
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import org.scalatest.tagobjects.Slow
import cats.data.Validated._
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse }

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

  import ch.chuv.lren.woken.service.TestServices._

  val noDbConfig =
    DatabaseConfiguration(dbiDriver = "DBI",
                          dbApiDriver = "DBAPI",
                          jdbcDriver = "java.lang.String",
                          jdbcUrl = "",
                          host = "",
                          port = 0,
                          database = "db",
                          user = "",
                          password = "")
  val noJobsConf =
    JobsConfiguration("none",
                      "noone",
                      "http://nowhere",
                      "features",
                      "features",
                      "features",
                      "results",
                      "meta")

  val fakeFeaturesDAL = FeaturesDAL(noDbConfig)

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    ConfigUtil.lift(
      ExperimentActor.Job(
        jobId = UUID.randomUUID().toString,
        inputDb = "",
        inputTable = "",
        query = query,
        metadata = Nil
      )
    )

  def miningQuery2job(query: MiningQuery): Validation[DockerJob] = {
    val featuresQuery = query.features("test", excludeNullValues = true, None)

    ConfigUtil.lift(
      DockerJob(
        jobId = UUID.randomUUID().toString,
        dockerImage = "",
        inputDb = "",
        query = featuresQuery,
        algorithmSpec = query.algorithm,
        metadata = Nil
      )
    )
  }

  class MasterRouterUnderTest(appConfiguration: AppConfiguration,
                              coordinatorConfig: CoordinatorConfig,
                              dispatcherService: DispatcherService,
                              algorithmLibraryService: AlgorithmLibraryService,
                              algorithmLookup: String => Validation[AlgorithmDefinition],
                              datasetService: DatasetService)
      extends MasterRouter(appConfiguration,
                           coordinatorConfig,
                           dispatcherService,
                           algorithmLibraryService,
                           algorithmLookup,
                           datasetService,
                           experimentQuery2job,
                           miningQuery2job) {

    override def newExperimentActor: ActorRef =
      system.actorOf(Props(new FakeExperimentActor()))

    override def newCoordinatorActor: ActorRef =
      system.actorOf(Props(new FakeCoordinatorActor()))

    override def initValidationWorker: ActorRef =
      context.actorOf(FakeActors.echoActorProps)

    override def initScoringWorker: ActorRef =
      context.actorOf(FakeActors.echoActorProps)

  }

  val config: Config = ConfigFactory.load("test.conf")
  val appConfig: AppConfiguration = AppConfiguration
    .read(config)
    .valueOr(
      e => throw new IllegalStateException(s"Invalid configuration: ${e.toList.mkString(", ")}")
    )

  val jdbcConfigs: String => ConfigUtil.Validation[DatabaseConfiguration] = _ => Valid(noDbConfig)

  val coordinatorConfig: CoordinatorConfig = CoordinatorConfig(
    system.actorOf(FakeActors.echoActorProps),
    None,
    fakeFeaturesDAL,
    jobResultService,
    noJobsConf,
    jdbcConfigs.apply
  )

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val wokenService: WokenService = WokenService("test")

  val dispatcherService: DispatcherService =
    DispatcherService(DatasetsConfiguration.datasets(config), wokenService)

  val datasetService: DatasetService = ConfBasedDatasetService(config)

  val user: UserId = UserId("test")

  "Master Actor " must {

    val router =
      system.actorOf(
        Props(
          new MasterRouterUnderTest(appConfig,
                                    coordinatorConfig,
                                    dispatcherService,
                                    algorithmLibraryService,
                                    AlgorithmsConfiguration.factory(config),
                                    datasetService)
        )
      )

    "starts new experiments" in {

      val limit = appConfig.masterRouterConfig.experimentActorsLimit

      (1 to limit).foreach { _ =>
        router ! ExperimentQuery(
          user = user,
          variables = Nil,
          covariables = Nil,
          grouping = Nil,
          filters = None,
          targetTable = None,
          trainingDatasets = Set(),
          testingDatasets = Set(),
          validationDatasets = Set(),
          algorithms = Nil,
          validations = Nil,
          executionPlan = None
        )

      }

      within(limit seconds) {

        (1 to limit).foreach { _ =>
          expectMsgType[QueryResult](5 seconds)
        }

        expectNoMessage(1 seconds)
      }

      waitForEmptyQueue(router, limit)
    }

    "not start new experiments over the limit of concurrent experiments, then recover" taggedAs Slow in {

      val limit    = appConfig.masterRouterConfig.experimentActorsLimit
      val overflow = limit * 2

      (1 to overflow).foreach { _ =>
        router ! ExperimentQuery(
          user = user,
          variables = Nil,
          covariables = Nil,
          grouping = Nil,
          filters = None,
          targetTable = None,
          trainingDatasets = Set(),
          testingDatasets = Set(),
          validationDatasets = Set(),
          algorithms = Nil,
          validations = Nil,
          executionPlan = None
        )

      }

      var successfulStarts = 0
      var failures         = 0

      within(overflow seconds) {

        (1 to overflow).foreach { i =>
          expectMsgPF[Unit](5 seconds) {
            case QueryResult(_, _, _, _, _, Some(_), None) => successfulStarts += 1
            case QueryResult(_, _, _, _, _, None, Some(_)) => failures += 1
          }
        }

        expectNoMessage(1 seconds)
        Thread.sleep(100)
      }

      assert(failures > 0)

      (successfulStarts + failures) shouldBe overflow

      waitForEmptyQueue(router, overflow)

      // Now we check that after rate limits, the actor can recover and handle new requests

      (1 to limit).foreach { _ =>
        router ! ExperimentQuery(
          user = user,
          variables = Nil,
          covariables = Nil,
          grouping = Nil,
          filters = None,
          targetTable = None,
          trainingDatasets = Set(),
          testingDatasets = Set(),
          validationDatasets = Set(),
          algorithms = Nil,
          validations = Nil,
          executionPlan = None
        )

      }

      within(limit seconds) {

        (1 to limit).foreach { _ =>
          expectMsgType[QueryResult](5 seconds)
        }

        expectNoMessage(1 seconds)
      }

      waitForEmptyQueue(router, limit)

    }

    "return available datasets" in {

      router ! DatasetsQuery

      within(5 seconds) {
        val msg = expectMsgType[DatasetsResponse]
        msg.datasets should not be empty
      }
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
